import sqlite3
import subprocess
import json
import uuid
import base64
import random

# configs
DB_PATH = "/home/math_/BirdNET-Pi/scripts/birds.db"  # database path
TABLE_NAME = "detections" # table name
DESTINATION_EID = "dtn://rbpi_green/incoming"  # Destination EID
SOURCE_EID = "dtn://rbpi_station"  # name of local DTN node
DTNSEND_PATH = "/home/math_/.cargo/bin/dtnsend"  # DTNSEND path
AUDIO_FILE = "/home/math_/BirdSongs/Extracted/By_Date/2026-05-18/Rose-ringed_Parakeet/Rose-ringed_Parakeet-92-2026-05-18-birdnet-13:21:35.mp3" #example audio file

def get_pending_detections(limit=10):
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # cursor.execute('''SELECT rowid, *
    #                  FROM detections
    #                   WHERE rowid NOT IN 
    #                     (SELECT rowid FROM sent)
    #                  LIMIT ?''', (limit,))
    
    cursor.execute('''SELECT rowid, *
                     FROM detections
                     LIMIT ?''', (limit,))
    rows = cursor.fetchall()

    # obtains the columns name to put them in dictionaries
    column_names = [description[0] for description in cursor.description]
    conn.close()

    detections = [dict(zip(column_names, row)) for row in rows]
    return detections


def encode_audio(path: str) -> str:
    if not path or not os.path.isfile(path):
        return ""
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("ascii")

def send_bundle(detections, hash_id):
    
    if random. randint(1, 10) == 1:
        payload = {
            "source_eid": SOURCE_EID,
            "hash_id": hash_id,
            "detections": detections,
            "mp3_data": encode_audio(AUDIO_FILE), 
        }
    else:
        payload = {
            "source_eid": SOURCE_EID,
            "hash_id": hash_id,
            "detections": detections,
            "mp3_data": '' 
        }



    temp_path = "/tmp/dtn_payload.json"
    with open(temp_path, "w") as f:
        json.dump(payload, f)

    try:
        result = subprocess.run(
            [DTNSEND_PATH, "-r", DESTINATION_EID, temp_path],
            # input=payload.encode('utf-8'),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True
        )
        print("Bundle successfully sent")
        print(result.stdout.decode())
    except subprocess.CalledProcessError as e:
        print("Error to send bundle:")
        print(e.stderr.decode())


def check_sanity():
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()

    c.execute('''
        CREATE TABLE IF NOT EXISTS sent (
            rowid INTEGER,
            hash_id TEXT NOT NULL,
            sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    conn.commit()
    conn.close()


def register_sent_rows(rowids, hash_id):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()

    c.execute('''
        CREATE TABLE IF NOT EXISTS sent (
            rowid INTEGER,
            hash_id TEXT NOT NULL,
            sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')

    c.executemany(
        "INSERT INTO sent (rowid, hash_id) VALUES (?, ?)",
        [(rid, hash_id) for rid in rowids]
    )

    conn.commit()
    conn.close()


def main():
    check_sanity()
    pending = get_pending_detections()
    if not pending:
        print("No detection found.")
        return

    clean_detections = [{k: v for k, v in row.items() if k != "rowid"} for row in pending]
    rowids = [row["rowid"] for row in pending]
    hash_id = str(uuid.uuid4())

    send_bundle(clean_detections, hash_id)
    register_sent_rows(rowids, hash_id)

if __name__ == "__main__":
    main()
