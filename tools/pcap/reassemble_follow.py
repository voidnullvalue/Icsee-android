#!/usr/bin/env python3
"""Reassemble a tshark `follow,tcp,raw` dump into ordered per-direction byte
streams, then parse DVRIP frames out of each direction independently.

Usage:
    tshark -r pcap.pcap -q -z follow,tcp,raw,<stream> > stream.followraw.txt
    python3 reassemble_follow.py stream.followraw.txt

We reassemble TCP bytes first and only then look for DVRIP frames -- a raw
pcap packet is not the same thing as a DVRIP frame, and DVRIP frames can
span multiple TCP segments or multiple frames can share one segment.
"""
from __future__ import annotations

import sys
from pathlib import Path

from dvrip_common import iter_frames, looks_like_json

KNOWN_MESSAGE_NAMES = {
    1000: "OPMachine (Login)",
    1001: "Login response",
    1006: "OPTimeSetting/KeepAlive",
    1007: "KeepAlive response",
    1008: "OPSystemInfo",
    1009: "OPSystemInfo response",
    1010: "OPPreLoginNegotiate",
    1011: "OPPreLoginNegotiate response",
    1400: "OPPTZControl request",
    1401: "OPPTZControl response",
    1410: "OPMonitorClaim request",
    1411: "OPMonitorClaim response",
    1412: "OPMonitor Claim",
    1413: "OPMonitor Claim response",
    1414: "OPMonitor Start",
    1420: "OPTalk Claim",
    1422: "OPTalk",
    1423: "OPTalk response",
    1424: "OPTalk data",
}


def split_directions(text: str) -> tuple[bytes, bytes]:
    client = bytearray()
    server = bytearray()
    for line in text.splitlines():
        if line.startswith("=") or line.startswith("Follow:") or line.startswith("Filter:") or line.startswith("Node "):
            continue
        if not line.strip():
            continue
        if line.startswith("\t"):
            server += bytes.fromhex(line.strip())
        else:
            client += bytes.fromhex(line.strip())
    return bytes(client), bytes(server)


def describe(data: bytes, label: str) -> None:
    print(f"=== {label}: {len(data)} bytes ===")
    count = 0
    for frame in iter_frames(data):
        count += 1
        name = KNOWN_MESSAGE_NAMES.get(frame.msg_id, "?")
        preview = frame.payload[:200]
        is_json = looks_like_json(frame.payload)
        kind = "JSON" if is_json else "BINARY"
        print(
            f"[{label}] off={frame.stream_offset:6d} session={frame.session_hex} "
            f"seq={frame.seq:5d} msg={frame.msg_id:5d} ({name}) len={frame.payload_len:6d} {kind}"
        )
        if is_json:
            try:
                print("    " + preview.decode("utf-8", errors="replace").replace("\n", "\\n"))
            except Exception:
                pass
        else:
            print("    " + preview.hex())
    print(f"--- {count} frames parsed from {label} ---\n")


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <follow_raw.txt>", file=sys.stderr)
        sys.exit(1)
    text = Path(sys.argv[1]).read_text()
    client, server = split_directions(text)
    describe(client, "client->camera")
    describe(server, "camera->client")


if __name__ == "__main__":
    main()
