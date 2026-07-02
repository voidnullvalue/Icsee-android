#!/usr/bin/env python3
"""Round 3: pure-RSA hypotheses for message 1000 (EncryptAlgo:RSA_V1.5 might
describe the login payload's own encryption directly, separate from
CommunicateEncryptAlgo:AES which could describe only the *post-login*
transport). Also tries RSA/ECB/OAEP as an alternate padding scheme.
"""
import socket
import struct
import sys
import json
import hashlib
import base64
import os
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5, PKCS1_OAEP, AES

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


def negotiate():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect((HOST, PORT))
    s.sendall(make_frame(0, 0, 1010, (json.dumps({}) + "\n").encode() + b"\x00"))
    resp = recv_frame(s)
    negotiation = json.loads(resp["payload"].rstrip(b"\x00").decode())
    modulus_hex, exponent_hex = negotiation["PublicKey"].split(",")
    key = RSA.construct((int(modulus_hex, 16), int(exponent_hex, 16)))
    return s, key


def report(label, payload_bytes):
    print(f"=== {label} (payload {len(payload_bytes)} bytes) ===")
    try:
        s, rsa_key = negotiate()
        s.sendall(make_frame(0, 1, 1000, payload_bytes))
        resp = recv_frame(s)
        s.close()
        if resp is None:
            print("  no response")
            return None
        text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
        try:
            result = json.loads(text)
        except Exception:
            result = {"raw": text}
        print(f"  {result}")
        return result
    except Exception as e:
        print(f"  error: {e!r}")
        return None


if __name__ == "__main__":
    for login_type in ["DVRIP-Web", "DVRIP-Android"]:
        body = {
            "EncryptType": "MD5",
            "LoginType": login_type,
            "PassWord": sofia_hash(PASSWORD),
            "UserName": USERNAME,
        }
        json_bytes = json.dumps(body).encode()
        print(f"plaintext JSON length: {len(json_bytes)} bytes (RSA-1024/PKCS1v1.5 max is 117)")

        s, rsa_key = negotiate()
        cipher = PKCS1_v1_5.new(rsa_key)
        ct = cipher.encrypt(json_bytes)
        payload = base64.b64encode(ct) + b"\x00"
        s.sendall(make_frame(0, 1, 1000, payload))
        resp = recv_frame(s)
        s.close()
        print(f"=== pure RSA/PKCS1v1.5, LoginType={login_type} ===")
        if resp:
            text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
            print(f"  {text}")
        else:
            print("  no response")

    # Bare credentials, pure RSA, no LoginType/EncryptType wrapper.
    for body in [
        {"UserName": USERNAME, "PassWord": PASSWORD},
        {"UserName": USERNAME, "PassWord": sofia_hash(PASSWORD)},
        f"{USERNAME}\n{PASSWORD}",
    ]:
        s, rsa_key = negotiate()
        cipher = PKCS1_v1_5.new(rsa_key)
        raw = json.dumps(body).encode() if isinstance(body, dict) else body.encode()
        ct = cipher.encrypt(raw)
        payload = base64.b64encode(ct) + b"\x00"
        s.sendall(make_frame(0, 1, 1000, payload))
        resp = recv_frame(s)
        s.close()
        print(f"=== pure RSA/PKCS1v1.5, body={body!r} ===")
        if resp:
            text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
            print(f"  {text}")
        else:
            print("  no response")
