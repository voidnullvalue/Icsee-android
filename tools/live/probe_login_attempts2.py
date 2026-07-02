#!/usr/bin/env python3
"""Round 2 of login-format discovery, informed by round 1: every AES-key
hypothesis in round 1 returned the *same* Ret 117 (not the Ret 205 seen for
a totally-wrong-format legacy plaintext attempt), suggesting 117 might be a
generic "malformed encrypted login" code rather than "AES key wrong" -- or
it might mean something else entirely. This round establishes what a
clearly-garbage payload returns as a baseline, then broadens the JSON field
hypotheses.
"""
import socket
import struct
import sys
import json
import hashlib
import base64
import os
from Crypto.Cipher import AES

HOST = os.environ.get("ICSEE_TEST_HOST", "192.168.1.100")
PORT = int(os.environ.get("ICSEE_TEST_PORT", "34567"))
USERNAME = os.environ["ICSEE_TEST_USERNAME"]
PASSWORD = os.environ["ICSEE_TEST_PASSWORD"]


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
    table = "0123456789ABCDEFGHIJKLMNOPQRSTUV"
    digest = hashlib.md5(password.encode()).digest()
    out = []
    for i in range(0, len(digest), 2):
        b1, b2 = digest[i], digest[i + 1]
        n = (b1 + b2) % 32
        out.append(table[n])
    return "".join(out)


def pkcs7_pad(data: bytes, block_size: int = 16) -> bytes:
    pad_len = block_size - (len(data) % block_size)
    return data + bytes([pad_len]) * pad_len


def negotiate_and_send(session_payload_bytes, seq=1):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect((HOST, PORT))
    s.sendall(make_frame(0, 0, 1010, (json.dumps({}) + "\n").encode() + b"\x00"))
    resp = recv_frame(s)
    negotiation = json.loads(resp["payload"].rstrip(b"\x00").decode())
    s.sendall(make_frame(0, seq, 1000, session_payload_bytes))
    resp = recv_frame(s)
    s.close()
    if resp is None:
        return None, negotiation
    text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
    try:
        return json.loads(text), negotiation
    except Exception:
        return {"raw": text}, negotiation


def report(label, payload_bytes):
    print(f"=== {label} ===")
    try:
        result, _ = negotiate_and_send(payload_bytes)
        print(f"  {result}")
        return result
    except Exception as e:
        print(f"  error: {e!r}")
        return None


if __name__ == "__main__":
    # Baseline: obviously-garbage base64 text, same rough length class as our real attempts.
    garbage = base64.b64encode(os.urandom(64)) + b"\x00"
    report("garbage base64 baseline", garbage)

    # Baseline: garbage but block-aligned length like our AES attempts (matches ~96-byte ciphertext class).
    garbage2 = base64.b64encode(os.urandom(96)) + b"\x00"
    report("garbage base64 baseline (96 bytes)", garbage2)

    key = hashlib.md5(PASSWORD.encode()).digest()

    variants = []
    for login_type in ["DVRIP-Web", "DVRIP-Android", "NetSurveillance", "SOFIA"]:
        for pass_field in [("sofia", sofia_hash(PASSWORD)), ("plain", PASSWORD), ("md5hex", hashlib.md5(PASSWORD.encode()).hexdigest().upper())]:
            body = {
                "EncryptType": "MD5",
                "LoginType": login_type,
                "PassWord": pass_field[1],
                "UserName": USERNAME,
            }
            variants.append((f"LoginType={login_type} pass={pass_field[0]}", body))

    # Also try without EncryptType/LoginType at all -- bare credentials.
    variants.append(("bare UserName/PassWord(plain)", {"UserName": USERNAME, "PassWord": PASSWORD}))
    variants.append(("bare UserName/PassWord(sofia)", {"UserName": USERNAME, "PassWord": sofia_hash(PASSWORD)}))

    for label, body in variants:
        json_bytes = json.dumps(body).encode()
        cipher = AES.new(key, AES.MODE_ECB)
        ct = cipher.encrypt(pkcs7_pad(json_bytes))
        payload = base64.b64encode(ct) + b"\x00"
        result = report(f"AES-ECB key=MD5(pw), {label}", payload)
        if result and result.get("Ret") == 100:
            print(f"\n*** SUCCESS: {label} ***")
            sys.exit(0)
