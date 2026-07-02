#!/usr/bin/env python3
"""Export sanitized DVRIP frame metadata (no credential/key material, no raw
media bytes) from a tshark follow,tcp,raw dump, as JSON lines.

Usage:
    tshark -r pcap.pcap -q -z follow,tcp,raw,<stream> > s.txt
    python3 export_sanitized_metadata.py s.txt > s.metadata.jsonl

Each line describes one DVRIP frame: direction, session, sequence, message
id, payload length, and whether the payload looked like plaintext JSON or an
encrypted/binary blob -- never the payload bytes themselves.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from dvrip_common import iter_frames, looks_like_json
from reassemble_follow import split_directions


def frame_kind(payload: bytes) -> str:
    if looks_like_json(payload):
        return "plaintext_json"
    stripped = payload.rstrip(b"\x00")
    try:
        text = stripped.decode("ascii")
    except UnicodeDecodeError:
        return "binary"
    if text and all(c.isalnum() or c in "+/=" for c in text):
        return "base64_ascii"
    return "binary"


def export(data: bytes, direction: str):
    for frame in iter_frames(data):
        yield {
            "direction": direction,
            "stream_offset": frame.stream_offset,
            "session": frame.session_hex,
            "sequence": frame.seq,
            "message_id": frame.msg_id,
            "payload_len": frame.payload_len,
            "kind": frame_kind(frame.payload),
        }


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <follow_raw.txt>", file=sys.stderr)
        sys.exit(1)
    text = Path(sys.argv[1]).read_text()
    client, server = split_directions(text)
    for record in export(client, "client_to_camera"):
        print(json.dumps(record))
    for record in export(server, "camera_to_client"):
        print(json.dumps(record))


if __name__ == "__main__":
    main()
