#!/usr/bin/env python3
"""Download one recorded clip off the SD card via DVRIP OPPlayBack.

Correct XM sequence (per OpenIPC/python-dvr):
  - Claim on msg 1424: {"Name":"OPPlayBack","OPPlayBack":{"Action":"Claim",...}}
  - DownloadStart on msg 1420 (same body, Action="DownloadStart")
  - media/file bytes stream back on the SAME socket; a frame whose header
    length field is 0 marks the end
  - DownloadStop on msg 1420 to finish

Env: ICSEE_TEST_HOST/USERNAME/PASSWORD, optional ICSEE_CLIP, ICSEE_OUT.
"""
import json
import os
import socket
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sdcard_probe import Conn, frame, FILE_QUERY, FILE_RESP  # noqa: E402

PB_CLAIM = 1424       # OPPlayBack Claim
PB_CTRL = 1420        # OPPlayBack Start/DownloadStart/Stop
OUT = os.environ.get("ICSEE_OUT", "/tmp/claude-10199/-root/c731cbaf-5722-4f95-84fb-c0ce4cb49ec9/scratchpad/clip.h264")


def recv_frame(sock, timeout=8):
    """Return (msgid, payload_bytes) using the media header layout, or None."""
    sock.settimeout(timeout)
    hdr = b""
    try:
        while len(hdr) < 20:
            ch = sock.recv(20 - len(hdr))
            if not ch:
                return None
            hdr += ch
    except socket.timeout:
        return None
    # head,version,2x pad,session,seq,total,cur,msgid,len  (BB2xIIBBHI)
    _h, _v, _sess, _sq, _tot, _cur, mid, ln = struct.unpack("<BB2xIIBBHI", hdr)
    body = b""
    while len(body) < ln:
        ch = sock.recv(min(65536, ln - len(body)))
        if not ch:
            break
        body += ch
    return mid, body, ln


def query_first_clip(c, day):
    obj = {"Name": "OPFileQuery",
           "OPFileQuery": {"BeginTime": f"{day} 00:00:00", "EndTime": f"{day} 23:59:59",
                           "Channel": 0, "DriverTypeMask": "0x0000FFFF", "Event": "*",
                           "Type": "h264", "StreamType": "0x00000000"},
           "SessionID": c.sid_hex()}
    c.s.sendall(frame(c.sid, c.seq, FILE_QUERY, (json.dumps(obj) + "\n").encode() + b"\x00")); c.seq += 1
    for _ in range(30):
        r = recv_frame(c.s, 6)
        if not r:
            break
        mid, body, _ = r
        if mid == FILE_RESP:
            try:
                arr = json.loads(body.rstrip(b"\x00").decode("utf-8", "replace")).get("OPFileQuery")
            except Exception:
                arr = None
            if isinstance(arr, list) and arr:
                return arr
    return []


def pb_body(action, fn, st, en, c):
    return {"Name": "OPPlayBack",
            "OPPlayBack": {"Action": action, "StartTime": st, "EndTime": en,
                           "Parameter": {"PlayMode": "ByName", "FileName": fn,
                                         "StreamType": 0, "Value": 0, "TransMode": "TCP"}},
            "SessionID": c.sid_hex()}


def send(c, mid, obj):
    c.s.sendall(frame(c.sid, c.seq, mid, (json.dumps(obj) + "\n").encode() + b"\x00")); c.seq += 1


def main():
    c = Conn(); login = c.login()
    print(f"[login] session {login['SessionID']}")
    day = os.environ.get("ICSEE_DAY", "2026-07-09")
    clips = query_first_clip(c, day)
    if not clips:
        print("no clips"); return 1
    clip = clips[0]
    want = os.environ.get("ICSEE_CLIP")
    if want:
        clip = next((x for x in clips if want in x.get("FileName", "")), clip)
    fn, st, en = clip["FileName"], clip["BeginTime"], clip["EndTime"]
    exp = int(clip["FileLength"], 16)
    print(f"[clip]  {fn}\n        {st} -> {en}  expect {exp} bytes")

    send(c, PB_CLAIM, pb_body("Claim", fn, st, en, c))
    r = recv_frame(c.s, 6)
    if r:
        print(f"[claim] mid={r[0]} {r[1].rstrip(chr(0).encode()).decode('utf-8','replace')[:80]}")

    send(c, PB_CTRL, {**pb_body("DownloadStart", fn, st, en, c)})

    buf = bytearray(); mids = {}; frames = 0
    while True:
        r = recv_frame(c.s, 6)
        if r is None:
            print("[recv]  timeout"); break
        mid, body, ln = r
        mids[mid] = mids.get(mid, 0) + 1
        if ln == 0:
            print("[recv]  end-of-stream (len=0)"); break
        # control acks (1425/1421) are JSON; skip them, keep binary media
        if body[:1] == b"{":
            continue
        buf += body; frames += 1

    send(c, PB_CTRL, pb_body("DownloadStop", fn, st, en, c))
    with open(OUT, "wb") as f:
        f.write(buf)
    print(f"[recv]  mids={mids} data-frames={frames} collected={len(buf)}/{exp} bytes")
    print(f"[head]  {bytes(buf[:48]).hex(' ')}")
    print(f"[saved] {OUT}")
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
