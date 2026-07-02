#!/usr/bin/env python3
"""Live probe: send a legacy-style plaintext JSON login (LoginType DVRIP-Web,
MD5 password hash) to confirm the task brief's claim that this camera
rejects it. Uses an intentionally bogus/placeholder username+password --
this is expected and designed to fail; it is not a credential-guessing
attempt against a real account.
"""
import socket
import struct
import sys
import json
import hashlib

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.100"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 34567


def make_frame(session, seq, msg_id, payload):
    header = struct.pack("<BBHIIHHI", 0xFF, 1, 0, session, seq, 0, msg_id, len(payload))
    return header + payload


def recv_frame(sock, timeout=5):
    sock.settimeout(timeout)
    header = b""
    while len(header) < 20:
        chunk = sock.recv(20 - len(header))
        if not chunk:
            return None
        header += chunk
    magic, typ, _res, session, seq, _res2, msg_id, paylen = struct.unpack("<BBHIIHHI", header)
    payload = b""
    while len(payload) < paylen:
        chunk = sock.recv(min(65536, paylen - len(payload)))
        if not chunk:
            break
        payload += chunk
    return {"session": session, "seq": seq, "msg_id": msg_id, "payload": payload}


def sofia_hash(password: str) -> str:
    # dbuezas/icsee-ptz "sofia hash": md5 the password, then map hex nibble
    # PAIRS through a fixed table and take every other resulting char.
    table = "0123456789ABCDEFGHIJKLMNOPQRSTUV"
    digest = hashlib.md5(password.encode()).digest()
    out = []
    for i in range(0, len(digest), 2):
        b1, b2 = digest[i], digest[i + 1]
        n = (b1 + b2) % 32
        out.append(table[n])
    return "".join(out)


payload_obj = {
    "EncryptType": "MD5",
    "LoginType": "DVRIP-Web",
    "PassWord": sofia_hash("placeholder-not-a-real-password"),
    "UserName": "placeholder",
}
payload = (json.dumps(payload_obj) + "\n").encode() + b"\x00"

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(5)
s.connect((HOST, PORT))
frame = make_frame(0, 0, 1000, payload)
print("sent:", frame.hex())
s.sendall(frame)
resp = recv_frame(s)
if resp is None:
    print("no response / connection closed by peer")
else:
    print(f"response msg_id={resp['msg_id']} session=0x{resp['session']:08X} len={len(resp['payload'])}")
    print("raw:", resp["payload"])
s.close()
