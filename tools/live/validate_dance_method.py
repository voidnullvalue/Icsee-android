#!/usr/bin/env python3
"""Validate the dance easter-egg's camera-facing method against the live camera.

Reproduces exactly what the app does (DvripLoginNegotiator + PtzRequestBuilder +
TalkController) on the wire, with no RSA/AES negotiation:

  1. Login   -> msg 1000, plaintext JSON, PassWord = SofiaHash(password). Expect Ret:100 + SessionID.
  2. PTZ      -> msg 1400 OPPTZControl DirectionUp then compatibility stop. Expect Ret:100 (the "dance").
  3. OPTalk   -> msg 1434 claim + msg 1430 Start/Stop. Expect Ret:100 (the speaker channel).

Creds via env: ICSEE_TEST_HOST / ICSEE_TEST_USERNAME / ICSEE_TEST_PASSWORD.
"""
import hashlib
import json
import os
import socket
import struct
import sys

HOST = os.environ.get("ICSEE_TEST_HOST", "192.168.88.129")
PORT = int(os.environ.get("ICSEE_TEST_PORT", "34567"))
USERNAME = os.environ["ICSEE_TEST_USERNAME"]
PASSWORD = os.environ["ICSEE_TEST_PASSWORD"]

LOGIN_REQ, LOGIN_RESP = 1000, 1001
PTZ_REQ, PTZ_RESP = 1400, 1401
TALK_CLAIM, TALK_CLAIM_RESP = 1434, 1435
OPTALK_CTRL, OPTALK_CTRL_RESP = 1430, 1431
TALK_AUDIO_UPSTREAM = 1432
TALK_SUBHEADER = bytes([0x00, 0x00, 0x01, 0xFA, 0x0E, 0x02, 0x40, 0x01])

_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def sofia_hash(pw: str) -> str:
    d = hashlib.md5(pw.encode()).digest()
    return "".join(_CHARS[(d[2 * i] + d[2 * i + 1]) % 62] for i in range(8))


def frame(session: int, seq: int, msg_id: int, payload: bytes) -> bytes:
    # magic, type, reserved, session, seq, reserved, msg_id, paylen (little-endian, 20B)
    return struct.pack("<BBHIIHHI", 0xFF, 1, 0, session, seq, 0, msg_id, len(payload)) + payload


def send_json(sock, session, seq, msg_id, obj) -> dict:
    payload = (json.dumps(obj) if isinstance(obj, dict) else obj).encode() + b"\x00"
    sock.sendall(frame(session, seq, msg_id, payload))
    return recv_frame(sock)


def recv_frame(sock, timeout=5):
    sock.settimeout(timeout)
    hdr = b""
    try:
        while len(hdr) < 20:
            c = sock.recv(20 - len(hdr))
            if not c:
                return None
            hdr += c
    except socket.timeout:
        return None
    _m, _t, _r, session, seq, _r2, msg_id, paylen = struct.unpack("<BBHIIHHI", hdr)
    body = b""
    while len(body) < paylen:
        c = sock.recv(min(65536, paylen - len(body)))
        if not c:
            break
        body += c
    text = body.rstrip(b"\x00").decode(errors="replace")
    try:
        parsed = json.loads(text)
    except Exception:
        parsed = {"raw": text}
    return {"session": session, "msg_id": msg_id, "json": parsed}


def recv_until(sock, want_msg_id, max_frames=60):
    """Read frames until one matches want_msg_id, skipping interleaved audio
    downstream frames (msg 1433) the camera pushes once OPTalk is running."""
    for _ in range(max_frames):
        f = recv_frame(sock)
        if f is None:
            return None
        if f["msg_id"] == want_msg_id:
            return f
    return None


def alaw_tone(seconds=1.0, freq=880, rate=8000):
    """A ~freq Hz sine in G.711 A-law, as a list of 320-byte frames (40ms each)."""
    import audioop
    import math
    n = int(seconds * rate)
    pcm = bytearray()
    for i in range(n):
        v = int(0.5 * 32767 * math.sin(2 * math.pi * freq * i / rate))
        pcm += struct.pack("<h", v)
    alaw = audioop.lin2alaw(bytes(pcm), 2)
    return [alaw[i:i + 320].ljust(320, b"\x55") for i in range(0, len(alaw), 320)]


def ptz_body(command: str, session_hex: str, step: int, preset: int, tour: int) -> dict:
    return {
        "Name": "OPPTZControl",
        "SessionID": session_hex,
        "OPPTZControl": {
            "Command": command,
            "Parameter": {
                "AUX": {"Number": 0, "Status": "On"},
                "Channel": 0, "MenuOpts": "Enter", "Pattern": "Start",
                "Preset": preset, "Step": step, "Tour": tour,
            },
        },
    }


def main() -> int:
    print(f"target {HOST}:{PORT}  user={USERNAME}")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect((HOST, PORT))

    # 1) LOGIN
    login = send_json(s, 0, 0, LOGIN_REQ, {
        "EncryptType": "MD5", "LoginType": "DVRIP-Web",
        "PassWord": sofia_hash(PASSWORD), "UserName": USERNAME,
    })
    print(f"[login]  {login['json']}")
    if not login or login["json"].get("Ret") != 100:
        print("LOGIN FAILED -> cannot validate PTZ/OPTalk"); return 1
    sid_str = login["json"].get("SessionID", "0x0")
    sid = int(sid_str, 16)
    sid_hex = "0x%08X" % sid
    print(f"         session id {sid_str} (num {sid})")

    seq = 1
    ok = True

    # 2) PTZ -- a brief DirectionUp nudge then the compatibility stop (one dance beat)
    r = send_json(s, sid, seq, PTZ_REQ, ptz_body("DirectionUp", sid_hex, step=5, preset=0, tour=0)); seq += 1
    print(f"[ptz  ]  DirectionUp -> {r['json']}")
    ok &= (r["json"].get("Ret") == 100)
    r = send_json(s, sid, seq, PTZ_REQ, ptz_body("DirectionUp", sid_hex, step=5, preset=-1, tour=0)); seq += 1
    print(f"[ptz  ]  stop        -> {r['json']}")
    ok &= (r["json"].get("Ret") == 100)

    # 3) OPTalk -- open the speaker channel (claim + Start), push a real tone
    #    out the speaker (msg 1432 upstream), then release. recv_until skips the
    #    camera's own downstream audio (1433) to find the control acks.
    talk_sid = "0x%010x" % sid
    s.sendall(frame(sid, seq, TALK_CLAIM,
        ('{"Name":"OPTalk","OPTalk":{"Action":"Claim","AudioFormat":{"BitRate":128,"EncodeType":"G711_ALAW","SampleBit":8,"SampleRate":8}},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    r = recv_until(s, TALK_CLAIM_RESP)
    print(f"[optalk]  claim -> {r['json'] if r else None}")
    ok &= bool(r) and r["json"].get("Ret") == 100

    s.sendall(frame(sid, seq, OPTALK_CTRL,
        ('{"Name":"OPTalk","OPTalk":{"Action":"Start"},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    r = recv_until(s, OPTALK_CTRL_RESP)
    print(f"[optalk]  start -> {r['json'] if r else None}")
    ok &= bool(r) and r["json"].get("Ret") == 100

    frames = alaw_tone(seconds=1.0, freq=880)
    for chunk in frames:
        s.sendall(frame(sid, 0, TALK_AUDIO_UPSTREAM, TALK_SUBHEADER + chunk))
    print(f"[optalk]  pushed {len(frames)} A-law frames (~1s tone) to the speaker")

    s.sendall(frame(sid, seq, OPTALK_CTRL,
        ('{"Name":"OPTalk","OPTalk":{"Action":"Stop"},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    r = recv_until(s, OPTALK_CTRL_RESP)
    print(f"[optalk]  stop  -> {r['json'] if r else None}")

    s.close()
    print("\nRESULT:", "ALL METHODS VALIDATED (Ret:100)" if ok else "SOME METHOD FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
