#!/usr/bin/env python3
"""Full easter-egg end-to-end against the live camera, from this environment.

Reproduces the whole dance pipeline the app runs (FileAudioSource ->
TalkController for the speaker, BeatDetector -> DanceChoreography for PTZ),
but sourced from the *actual* audio of the app's YouTube clip
(youtu.be/Z6dqIYKIBSU), decoded here to 8 kHz mono:

  login (SofiaHash)  ->  OPTalk claim  ->  for each 320-sample/40 ms chunk:
      * G.711 A-law encode -> msg 1432 upstream  (sound out the camera speaker)
      * BeatDetector.onChunk -> on a beat, advance DanceChoreography.DEFAULT_MOVES
        and send that OPPTZControl move (msg 1400)  (PTZ dances to the beat)

Ports BeatDetector (historySize 32, 1.4x, 220 ms) and DanceChoreography
(STEP 6, RIGHT/LEFT/UP/DOWN/RIGHT/LEFT/stop) 1:1 from the Kotlin.

Env: ICSEE_TEST_HOST/USERNAME/PASSWORD, PCM path, START/DURATION seconds.
"""
import audioop
import hashlib
import json
import os
import socket
import struct
import sys
import time

HOST = os.environ.get("ICSEE_TEST_HOST", "192.168.88.129")
PORT = int(os.environ.get("ICSEE_TEST_PORT", "34567"))
USERNAME = os.environ["ICSEE_TEST_USERNAME"]
PASSWORD = os.environ["ICSEE_TEST_PASSWORD"]
PCM = os.environ.get("ICSEE_PCM", "yt_pcm_8k_mono.raw")
START_S = float(os.environ.get("ICSEE_START", "30"))
DURATION_S = float(os.environ.get("ICSEE_DURATION", "25"))

LOGIN_REQ = 1000
PTZ_REQ = 1400
TALK_CLAIM, TALK_CLAIM_RESP = 1434, 1435
OPTALK_CTRL = 1430
TALK_AUDIO_UPSTREAM = 1432
TALK_SUBHEADER = bytes([0x00, 0x00, 0x01, 0xFA, 0x0E, 0x02, 0x40, 0x01])

CHUNK = 320          # samples per frame == 40 ms at 8 kHz (AUDIO_PAYLOAD_SIZE)
STEP = 10            # DanceChoreography.STEP (max) -- exaggerated
DEFAULT_MOVES = ["DirectionRight", "DirectionRight", "DirectionLeft", "DirectionLeft",
                 "DirectionUp", "DirectionDown",
                 "DirectionLeftUp", "DirectionRightDown",
                 "DirectionRightUp", "DirectionLeftDown",
                 "DirectionRight", "DirectionLeft", None]

_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def sofia_hash(pw):
    d = hashlib.md5(pw.encode()).digest()
    return "".join(_CHARS[(d[2 * i] + d[2 * i + 1]) % 62] for i in range(8))


def frame(session, seq, msg_id, payload):
    return struct.pack("<BBHIIHHI", 0xFF, 1, 0, session, seq, 0, msg_id, len(payload)) + payload


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
    try:
        j = json.loads(body.rstrip(b"\x00").decode(errors="replace"))
    except Exception:
        j = None
    return {"msg_id": msg_id, "json": j}


def recv_until(sock, want, max_frames=80):
    for _ in range(max_frames):
        f = recv_frame(sock)
        if f is None:
            return None
        if f["msg_id"] == want:
            return f
    return None


def drain(sock):
    """Non-blocking read to keep the camera's downstream audio from backing up."""
    sock.setblocking(False)
    try:
        while sock.recv(65536):
            pass
    except (BlockingIOError, socket.error):
        pass
    finally:
        sock.setblocking(True)


class BeatDetector:
    def __init__(self, history=32, mult=1.4, min_interval_ms=220):
        self.history, self.mult, self.min_interval = history, mult, min_interval_ms
        self.energies, self.last = [], 0.0

    def on_chunk(self, samples, now_ms):
        energy = sum(v * v for v in samples)
        is_beat = (len(self.energies) >= self.history // 2
                   and energy > (sum(self.energies) / len(self.energies)) * self.mult
                   and (now_ms - self.last) >= self.min_interval)
        self.energies.append(energy)
        if len(self.energies) > self.history:
            self.energies.pop(0)
        if is_beat:
            self.last = now_ms
        return is_beat


def ptz_payload(command, sid_hex, preset):
    return (json.dumps({
        "Name": "OPPTZControl", "SessionID": sid_hex,
        "OPPTZControl": {"Command": command, "Parameter": {
            "AUX": {"Number": 0, "Status": "On"}, "Channel": 0,
            "MenuOpts": "Enter", "Pattern": "Start",
            "Preset": preset, "Step": STEP if preset != -1 else 5, "Tour": 0}},
    }) + "\n").encode() + b"\x00"


def main():
    path = PCM if os.path.isabs(PCM) else os.path.join(os.path.dirname(__file__), PCM)
    if not os.path.exists(path):
        # allow running from the scratchpad where the PCM was produced
        alt = os.environ.get("ICSEE_PCM_ABS")
        path = alt if alt and os.path.exists(alt) else path
    with open(path, "rb") as f:
        f.seek(int(START_S * 8000) * 2)
        pcm = f.read(int(DURATION_S * 8000) * 2)
    total_chunks = len(pcm) // (CHUNK * 2)
    print(f"target {HOST}:{PORT} user={USERNAME}  audio={path}")
    print(f"streaming {total_chunks} frames (~{total_chunks*0.04:.0f}s) from t={START_S:.0f}s")

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect((HOST, PORT))

    s.sendall(frame(0, 0, LOGIN_REQ, (json.dumps({
        "EncryptType": "MD5", "LoginType": "DVRIP-Web",
        "PassWord": sofia_hash(PASSWORD), "UserName": USERNAME}) + "\n").encode() + b"\x00"))
    login = recv_frame(s)
    if not login or not login["json"] or login["json"].get("Ret") != 100:
        print("LOGIN FAILED:", login and login["json"]); return 1
    sid = int(login["json"]["SessionID"], 16)
    sid_hex = "0x%08X" % sid
    print(f"[login]  Ret:100  session {login['json']['SessionID']}")

    seq = 1
    talk_sid = "0x%010x" % sid
    s.sendall(frame(sid, seq, TALK_CLAIM, ('{"Name":"OPTalk","OPTalk":{"Action":"Claim","AudioFormat":{"BitRate":128,"EncodeType":"G711_ALAW","SampleBit":8,"SampleRate":8}},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    claim = recv_until(s, TALK_CLAIM_RESP)
    print(f"[optalk] claim Ret:{claim['json'].get('Ret') if claim and claim['json'] else '?'}")
    s.sendall(frame(sid, seq, OPTALK_CTRL, ('{"Name":"OPTalk","OPTalk":{"Action":"Start"},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    drain(s)

    beat = BeatDetector()
    move_i = beats = ptz_sent = 0
    t0 = time.monotonic()
    for i in range(total_chunks):
        raw = pcm[i * CHUNK * 2:(i + 1) * CHUNK * 2]
        samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
        alaw = audioop.lin2alaw(raw, 2).ljust(CHUNK, b"\x55")
        s.sendall(frame(sid, 0, TALK_AUDIO_UPSTREAM, TALK_SUBHEADER + alaw))

        if beat.on_chunk(samples, time.monotonic() * 1000):
            beats += 1
            cmd = DEFAULT_MOVES[move_i % len(DEFAULT_MOVES)]
            move_i += 1
            if cmd is not None:
                s.sendall(frame(sid, seq, PTZ_REQ, ptz_payload(cmd, sid_hex, preset=0))); seq += 1
            else:
                s.sendall(frame(sid, seq, PTZ_REQ, ptz_payload("DirectionUp", sid_hex, preset=-1))); seq += 1
            ptz_sent += 1

        if i % 25 == 0:
            drain(s)
        # pace to real playback time (40 ms/frame) using an absolute clock
        target = t0 + (i + 1) * 0.04
        dt = target - time.monotonic()
        if dt > 0:
            time.sleep(dt)

    # release: PTZ stop + OPTalk stop
    s.sendall(frame(sid, seq, PTZ_REQ, ptz_payload("DirectionUp", sid_hex, preset=-1))); seq += 1
    s.sendall(frame(sid, seq, OPTALK_CTRL, ('{"Name":"OPTalk","OPTalk":{"Action":"Stop"},"SessionID":"%s"}' % talk_sid).encode() + b"\x00")); seq += 1
    drain(s)
    s.close()
    print(f"\nstreamed {total_chunks} audio frames; detected {beats} beats -> sent {ptz_sent} PTZ moves")
    print("RESULT: END-TO-END OK -- real YouTube audio streamed to speaker + beat-driven PTZ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
