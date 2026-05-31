#!/usr/bin/env python3
"""BLE convergence layer for dtn7-rs.

Bridges the local dtn7 daemon (via its ECLA WebSocket on /ws/ecla) to
nearby BLE peers running the same protocol.

"""

import asyncio
import json
import logging
import re
import subprocess
import sys
import time
from typing import Dict, Optional

import websockets
from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData
from bless import (
    BlessGATTCharacteristic,
    BlessServer,
    GATTAttributePermissions,
    GATTCharacteristicProperties,
)
from dbus_fast.aio import MessageBus
from dbus_fast.constants import BusType
from dbus_fast.service import ServiceInterface, method


SERVICE_UUID = "5b1e93fe-7eaa-4f5c-93b1-bdc0a1f1d703"
RX_CHAR_UUID = "5b1e93fe-7eaa-4f5c-93b1-bdc0a1f1d704"

FLAG_START = 0x01
FLAG_END = 0x02
FLAG_SINGLE = 0x04

DTND_ECLA_URL = "ws://127.0.0.1:3000/ws/ecla"
CLA_NAME = "BleCla"
ADVERTISED_NAME = "dtn-pi"

# Assumes the phone negotiates MTU 517 (it does, see BleCla.kt).
# Drop to ~180 if you ever pair a peer that stays at the default MTU.
DEFAULT_MAX_CHUNK = 500

# One outbound peer at a time

MAX_ACTIVE_PEERS = 1
FAILED_BACKOFF_S = 30
RECONNECT_BACKOFF_S = 3

log = logging.getLogger("ble_cla")


def normalize_addr(addr: Optional[str]) -> Optional[str]:
    """Drop the ":<port>" suffix dtn7 appends when dispatching to an ECLA."""
    return re.sub(r":\d+$", "", addr) if addr else addr


# --- Framing ----------------------------------------------------------------

def fragment(payload: bytes, max_chunk: int = DEFAULT_MAX_CHUNK) -> list:
    """Split a payload into chunks of `[flag][bytes]`."""
    if max_chunk < 2:
        raise ValueError("max_chunk must be >= 2")
    body = max_chunk - 1
    if len(payload) <= body:
        return [bytes([FLAG_SINGLE]) + payload]
    chunks, offset, first = [], 0, True
    while offset < len(payload):
        take = min(body, len(payload) - offset)
        last = offset + take >= len(payload)
        flag = FLAG_START if first else (FLAG_END if last else 0)
        chunks.append(bytes([flag]) + payload[offset:offset + take])
        offset += take
        first = False
    return chunks


class Reassembler:
    """Per-peer chunk reassembly"""

    def __init__(self) -> None:
        self._buffers: Dict[str, bytearray] = {}

    def feed(self, peer: str, chunk: bytes) -> Optional[bytes]:
        if not chunk:
            return None
        flag, payload = chunk[0], chunk[1:]
        if flag & FLAG_SINGLE:
            self._buffers.pop(peer, None)
            return bytes(payload)
        if flag & FLAG_START:
            self._buffers[peer] = bytearray(payload)
            return None
        buf = self._buffers.get(peer)
        if buf is None:
            return None
        buf.extend(payload)
        if flag & FLAG_END:
            del self._buffers[peer]
            return bytes(buf)
        return None


# --- ECLA WebSocket client 

class EclaClient:
    """Async client for the dtn7-rs ECLA WebSocket"""

    def __init__(self, on_registered, on_beacon, on_forward_data,
                 url: str = DTND_ECLA_URL, cla_name: str = CLA_NAME) -> None:
        self.url = url
        self.cla_name = cla_name
        self.on_registered = on_registered
        self.on_beacon = on_beacon
        self.on_forward_data = on_forward_data
        self.ws = None
        self.node_id: Optional[str] = None

    async def run(self) -> None:
        while True:
            try:

                async with websockets.connect(
                    self.url, ping_interval=None, ping_timeout=None,
                ) as ws:
                    self.ws = ws
                    log.info("ECLA connected, registering as %r", self.cla_name)
                    await ws.send(json.dumps({
                        "type": "Register",
                        "name": self.cla_name,
                        "enable_beacon": True,
                    }))
                    async for raw in ws:
                        await self._handle(raw)
            except Exception as exc:
                log.warning("ECLA WS error (%s); retrying in %ds",
                            exc, RECONNECT_BACKOFF_S)
                self.ws = None
                await asyncio.sleep(RECONNECT_BACKOFF_S)

    async def _handle(self, raw: str) -> None:
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            log.warning("Bad JSON from dtnd: %s", raw)
            return
        kind = obj.get("type")
        if kind == "Registered":
            self.node_id = obj.get("nodeid")
            log.info("ECLA registered as %s", self.node_id)
            await self.on_registered(self.node_id)
        elif kind == "Beacon":
            await self.on_beacon(obj)
        elif kind == "ForwardData":
            await self.on_forward_data(obj)
        elif kind == "Error":
            log.error("dtnd error: %s", obj.get("reason"))
        else:
            log.warning("Unknown ECLA packet: %s", obj)

    async def send(self, pkt: dict) -> None:
        if self.ws is None:
            log.warning("Dropping %s — WS not connected", pkt.get("type"))
            return
        await self.ws.send(json.dumps(pkt))


# --- BlueZ pairing agent


class _PairingAgent(ServiceInterface):
    PATH = "/dtn/pi_peer/agent"

    def __init__(self) -> None:
        super().__init__("org.bluez.Agent1")

    @method()
    def Release(self): pass

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"): pass  # noqa: F821

    @method()
    def RequestPinCode(self, device: "o") -> "s": return "0000"  # noqa: F821

    @method()
    def RequestPasskey(self, device: "o") -> "u": return 0  # noqa: F821

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"): pass  # noqa: F821

    @method()
    def DisplayPinCode(self, device: "o", pincode: "s"): pass  # noqa: F821

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"): pass  # noqa: F821

    @method()
    def RequestAuthorization(self, device: "o"): pass  # noqa: F821

    @method()
    def Cancel(self): pass


async def register_pairing_agent() -> Optional[MessageBus]:
    try:
        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    except Exception as exc:
        log.warning("D-Bus system bus unavailable: %s", exc)
        return None

    bus.export(_PairingAgent.PATH, _PairingAgent())
    try:
        introspection = await bus.introspect("org.bluez", "/org/bluez")
        manager = bus.get_proxy_object(
            "org.bluez", "/org/bluez", introspection,
        ).get_interface("org.bluez.AgentManager1")
        try:
            await manager.call_unregister_agent(_PairingAgent.PATH)
        except Exception:
            pass
        await manager.call_register_agent(_PairingAgent.PATH, "NoInputNoOutput")
        await manager.call_request_default_agent(_PairingAgent.PATH)
        log.info("Pairing agent registered (Just Works)")
        return bus
    except Exception as exc:
        log.warning("Could not register pairing agent: %s", exc)
        return None


def configure_adapter() -> None:
    """Pairable on, discoverable off."""
    for cmd in (
        ["bluetoothctl", "pairable", "on"],
        ["bluetoothctl", "discoverable", "off"],
    ):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            if r.returncode != 0:
                log.warning("%s: %s", " ".join(cmd), r.stderr.strip())
        except Exception as exc:
            log.warning("Failed to run %s: %s", " ".join(cmd), exc)


# --- Top-level bridge 

class BleCla:
    def __init__(self) -> None:
        self.ecla = EclaClient(
            on_registered=self._on_registered,
            on_beacon=self._on_beacon_from_daemon,
            on_forward_data=self._on_forward_data_from_daemon,
        )
        self.reassembler = Reassembler()

        self.peers_by_addr: Dict[str, BleakClient] = {}
        self.peers_by_node: Dict[str, BleakClient] = {}
        self.addr_to_node: Dict[str, str] = {}

        self._connecting: set = set()
        self._failed_at: Dict[str, float] = {}
        self._scanner_task: Optional[asyncio.Task] = None
        self._server: Optional[BlessServer] = None
        self._agent_bus: Optional[MessageBus] = None

    async def run(self) -> None:
        await self.ecla.run()

    async def _on_registered(self, node_id: str) -> None:
        try:
            await self._start_gatt_server()
        except Exception:
            log.exception("BLE peripheral setup failed")
        if self._scanner_task is None:
            self._scanner_task = asyncio.create_task(self._scanner_loop())

    async def _start_gatt_server(self) -> None:
        loop = asyncio.get_running_loop()

        def read_request(_char: BlessGATTCharacteristic, **_) -> bytearray:
            return bytearray()

        def write_request(char: BlessGATTCharacteristic,
                          value: bytearray, **kwargs) -> None:
            if char.uuid.lower() != RX_CHAR_UUID.lower():
                return
            #TODO: find a way to differenciate two different devices, cuz rn we use one global key constant
            # fine for a single connected phone
            peer = str(kwargs.get("device", "phone"))
            asyncio.run_coroutine_threadsafe(
                self._on_ble_write(peer, bytes(value)), loop,
            )

        server = BlessServer(name=ADVERTISED_NAME)
        server.read_request_func = read_request
        server.write_request_func = write_request

        await server.add_new_service(SERVICE_UUID)
        await server.add_new_characteristic(
            SERVICE_UUID,
            RX_CHAR_UUID,
            (GATTCharacteristicProperties.write
             | GATTCharacteristicProperties.write_without_response),
            None,
            GATTAttributePermissions.writeable,
        )
        await server.start()
        self._server = server
        log.info("GATT server up. advertising %r (service %s)",
                 ADVERTISED_NAME, SERVICE_UUID)

        self._agent_bus = await register_pairing_agent()
        configure_adapter()

    # BLE -> ECLA -----------------------------------------------------------

    async def _on_ble_write(self, peer: str, chunk: bytes) -> None:
        assembled = self.reassembler.feed(peer, chunk)
        if assembled is None:
            return
        try:
            obj = json.loads(assembled.decode("utf-8"))
        except Exception as exc:
            log.warning("Bad JSON from BLE peer %s: %s", peer, exc)
            return

        kind = obj.get("type")
        if kind == "Beacon":
            node = normalize_addr(obj.get("addr"))
            if node:
                self.addr_to_node[peer] = node
                for mac, client in self.peers_by_addr.items():
                    self.peers_by_node[node] = client
                    self.addr_to_node[mac] = node
                log.info("Peer learned: %s (outbound clients: %d)",
                         node, len(self.peers_by_addr))
            await self.ecla.send(obj)
        elif kind == "ForwardData":
            await self.ecla.send(obj)
        else:
            log.warning("Unknown packet from peer: %s", obj)

    # ECLA -> BLE -----------------------------------------------------------

    async def _on_beacon_from_daemon(self, pkt: dict) -> None:
        if not self.peers_by_addr:
            return
        outgoing = dict(pkt, addr=self.ecla.node_id)
        payload = json.dumps(outgoing).encode("utf-8")
        for client in list(self.peers_by_addr.values()):
            await self._send_to_peer(client, payload)

    async def _on_forward_data_from_daemon(self, pkt: dict) -> None:
        dst = pkt.get("dst")
        client = self.peers_by_node.get(normalize_addr(dst))
        if client is None and self.peers_by_addr:
            # With MAX_ACTIVE_PEERS=1 the outbound is unambiguous; fall back
            # to it if peers_by_node hasn't been bound yet (e.g. the link
            # reconnected after the last beacon-driven bind).
            client = next(iter(self.peers_by_addr.values()))
        if client is None:
            log.warning("No BLE peer for %s; dropping (known: %s)",
                        dst, list(self.peers_by_node))
            return
        outgoing = dict(pkt, src=self.ecla.node_id)
        payload = json.dumps(outgoing).encode("utf-8")
        log.info("Forwarding bundle to %s (%d bytes)", dst, len(payload))
        await self._send_to_peer(client, payload)

    async def _send_to_peer(self, client: BleakClient, payload: bytes) -> None:
        if not client.is_connected:
            return
        for chunk in fragment(payload, DEFAULT_MAX_CHUNK):
            try:
                await client.write_gatt_char(RX_CHAR_UUID, chunk, response=False)
            except Exception as exc:
                log.warning("Write to %s failed: %s", client.address, exc)
                return

    # Central role

    async def _scanner_loop(self) -> None:
        def on_advertisement(device: BLEDevice, adv: AdvertisementData) -> None:
            if SERVICE_UUID.lower() not in [u.lower() for u in (adv.service_uuids or [])]:
                return
            addr = device.address
            if addr in self.peers_by_addr or addr in self._connecting:
                return
            last_fail = self._failed_at.get(addr)
            if last_fail and time.monotonic() - last_fail < FAILED_BACKOFF_S:
                return
            if len(self.peers_by_addr) + len(self._connecting) >= MAX_ACTIVE_PEERS:
                return
            self._connecting.add(addr)
            asyncio.create_task(self._connect_peer(device))

        while True:
            try:
                async with BleakScanner(
                    detection_callback=on_advertisement,
                    service_uuids=[SERVICE_UUID],
                ):
                    await asyncio.sleep(60)
            except Exception as exc:
                log.warning("Scanner error (%s); retrying in 5s", exc)
                await asyncio.sleep(5)

    async def _connect_peer(self, device: BLEDevice) -> None:
        addr = device.address
        if addr in self.peers_by_addr:
            self._connecting.discard(addr)
            return
        log.info("Connecting to %s (%s)", addr, device.name or "?")

        def on_disconnect(c: BleakClient) -> None:
            self.peers_by_addr.pop(c.address, None)
            self._connecting.discard(c.address)
            node = self.addr_to_node.pop(c.address, None)
            if node:
                self.peers_by_node.pop(node, None)
            log.info("Peer %s disconnected", c.address)

        client = BleakClient(device, disconnected_callback=on_disconnect)
        try:
            await client.connect()
            self.peers_by_addr[addr] = client
            self._failed_at.pop(addr, None)
            log.info("Connected to %s", addr)
            for node in set(self.addr_to_node.values()):
                self.peers_by_node[node] = client
        except Exception as exc:
            log.warning("Connect to %s failed: %s", addr, exc)
            self._failed_at[addr] = time.monotonic()
        finally:
            self._connecting.discard(addr)


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    try:
        await BleCla().run()
    except asyncio.CancelledError:
        pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
