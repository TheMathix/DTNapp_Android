# Pi-side BLE peer for the MyDtnApp DTN

This folder turns a Raspberry Pi (3 or newer, with built-in BLE) into the
second DTN node so you can test the phone's BLE convergence layer end-to-end
with only one phone.

It runs two processes on the Pi:

1. **`dtnd`** — the standard dtn7-rs daemon, built from source. Same Rust binary
   the phone embeds, just running natively on Linux. Configured via `dtnd.toml`
   in this folder.
2. **`ble_cla.py`** — a Python script that's functionally identical to
   `BleCla.kt` on the phone: it connects to `dtnd`'s ECLA WebSocket and bridges
   bundles between dtn7 and BLE.

With both running, the Pi is a peer dtn7 node that talks to your S24 Ultra
over BLE. Beacons flow both ways; bundles arrive on either side's
`/incoming` endpoint exactly as if a second phone were present.

---

## One-time Pi setup

Assumes Raspberry Pi OS Bookworm or later (the default on Pi 4/5 currently).
SSH into the Pi and run:

### 1. System prerequisites

```bash
sudo apt update
sudo apt install -y git build-essential pkg-config libssl-dev \
                    bluez python3-pip python3-venv
```

Confirm BLE is alive:

```bash
hciconfig
# you should see hci0 with `UP RUNNING` and an address. If it's DOWN:
sudo hciconfig hci0 up
```

### 2. Install Rust and build dtn7-rs

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
# accept defaults; then in a new shell:
source $HOME/.cargo/env

# Build dtnd from source. This takes ~5 min on a Pi 4.
cargo install --locked --git https://github.com/dtn7/dtn7-rs dtn7
# the binary lands in ~/.cargo/bin/dtnd
```

### 3. Python dependencies

```bash
cd ~/pi-peer            # wherever you put this folder
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 4. Run

Open two terminals (or use `tmux`):

**Terminal A — start dtn7:**

```bash
~/.cargo/bin/dtnd -c ~/pi-peer/dtnd.toml
```

You should see log lines about web port 3000, the ECLA being enabled, and
peer-discovery beacons being emitted every 2 seconds.

**Terminal B — start the BLE bridge:**

```bash
cd ~/pi-peer
source .venv/bin/activate
sudo .venv/bin/python ble_cla.py
```

`sudo` is needed because BlueZ advertising requires `CAP_NET_ADMIN`. To skip
the sudo permanently, you can grant that capability to the Python binary
inside your venv:

```bash
sudo setcap 'cap_net_admin,cap_net_raw+eip' .venv/bin/python3
# then run without sudo:
python ble_cla.py
```

You should see:

```
ECLA WS connected, registering as 'BleCla'
ECLA registered as nodeid=pi-collector
GATT server up; advertising service 5b1e93fe-…
```

---

## End-to-end test

1. Make sure the Pi is **not** running its own Wi-Fi hotspot (or the phone
   isn't connected to it), so the BLE path is what's being exercised.
2. Open the app on your S24 Ultra. Grant the BT permissions.
3. On the phone, watch `adb logcat -s DTN-BLE BleCla EclaClient DTN-MAIN`.
4. On the Pi, watch the `ble_cla.py` stdout.

Happy path:

- Pi log: `Connecting to peer XX:XX:XX:XX:XX:XX` then `Peer XX connected`
- Phone log: `Discovered peer YY:YY:YY:YY:YY:YY; initiating GATT` then
  `Peer YY ready`
- Both sides should see `Peer learned: node=… peer_key=…` (Pi) and
  `Peer learned: pi-collector -> XX` (phone) within ~2 s.
- Hit `http://127.0.0.1:3000/status/peers` on the phone (use a port-forward
  via adb if you need to reach it from your laptop) — you should see
  `pi-collector` listed via the `BleCla` convergence layer.
- Same check on the Pi via `curl 127.0.0.1:3000/status/peers` — you should
  see the phone's node-id.

## Sending a test bundle

From the Pi to the phone, drop a tiny bundle:

```bash
echo "hello from pi" | dtnsend -r dtn://android/incoming
```

(Replace `android` with whatever `nodeid` the phone reported in its logs;
the default in `assets/dtnd.toml` is `android`.)

The phone's `pollStatusBundles()` should pick it up within 5 seconds.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `Could not connect to D-Bus` | BlueZ isn't running. `sudo systemctl start bluetooth`. |
| `org.bluez.Error.NotPermitted` on advertise | Capabilities missing. Run with sudo, or use `setcap` as above. |
| Phone never sees the Pi in scan | Pi BLE radio is down (`hciconfig hci0 up`) or the service UUID doesn't match — both sides must use `5b1e93fe-7eaa-4f5c-93b1-bdc0a1f1d703`. |
| ECLA WS keeps reconnecting | `dtnd` isn't running, or its `webport` isn't 3000, or `[ecla] enabled = false`. |
| Bundles delivered twice | Expected — see the duplicate-connections note in `BleCla.kt`. dtn7 dedupes by bundle-id. |
| Slow throughput on big bundles | `DEFAULT_MAX_CHUNK = 20` in `ble_cla.py`. After the phone connects and negotiates a higher MTU, you can bump this to ~500. |
