#!/usr/bin/env python3
"""Live login-format discovery: try several defensible hypotheses for
message 1000's RSA/AES-wrapped credential payload against the real camera,
using real credentials supplied via environment variables (never
hardcoded, never committed). Stops as soon as one returns Ret:100 and
prints exactly what worked.

Usage:
    ICSEE_TEST_HOST=... ICSEE_TEST_USERNAME=... ICSEE_TEST_PASSWORD=... \
        python3 probe_login_attempts.py
"""
import socket
import struct
import sys
import json
import hashlib
import base64
import os
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5, AES

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


def negotiate():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect((HOST, PORT))
    frame = make_frame(0, 0, 1010, (json.dumps({}) + "\n").encode() + b"\x00")
    s.sendall(frame)
    resp = recv_frame(s)
    payload = json.loads(resp["payload"].rstrip(b"\x00").decode())
    modulus_hex, exponent_hex = payload["PublicKey"].split(",")
    key = RSA.construct((int(modulus_hex, 16), int(exponent_hex, 16)))
    return s, key, payload


def try_login(label, session_socket_pair_needed, build_payload_fn):
    print(f"=== {label} ===")
    s, rsa_key, negotiation = negotiate()
    try:
        payload = build_payload_fn(rsa_key)
        frame = make_frame(0, 1, 1000, payload)
        s.sendall(frame)
        resp = recv_frame(s)
        if resp is None:
            print("  no response / connection closed")
            return False
        print(f"  response msg_id={resp['msg_id']} session=0x{resp['session']:08X} payload={resp['payload']!r}")
        text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
        try:
            parsed = json.loads(text)
            print(f"  Ret={parsed.get('Ret')}")
            return parsed.get("Ret") == 100
        except Exception:
            print("  (non-JSON / still encrypted response)")
            return False
    finally:
        s.close()


def legacy_login_json():
    return {
        "EncryptType": "MD5",
        "LoginType": "DVRIP-Android",
        "PassWord": sofia_hash(PASSWORD),
        "UserName": USERNAME,
    }


# --- Hypothesis 1: AES-ECB, key = MD5(password) raw bytes, plaintext legacy-shaped JSON, PKCS7 padded ---
def hyp1(rsa_key):
    key = hashlib.md5(PASSWORD.encode()).digest()
    body = json.dumps(legacy_login_json()).encode()
    cipher = AES.new(key, AES.MODE_ECB)
    ct = cipher.encrypt(pkcs7_pad(body))
    return base64.b64encode(ct) + b"\x00"


# --- Hypothesis 2: AES-CBC zero IV, key = MD5(password) ---
def hyp2(rsa_key):
    key = hashlib.md5(PASSWORD.encode()).digest()
    body = json.dumps(legacy_login_json()).encode()
    cipher = AES.new(key, AES.MODE_CBC, iv=b"\x00" * 16)
    ct = cipher.encrypt(pkcs7_pad(body))
    return base64.b64encode(ct) + b"\x00"


# --- Hypothesis 3: AES-ECB, key = MD5(password), JSON has plaintext password (not sofia-hashed) ---
def hyp3(rsa_key):
    key = hashlib.md5(PASSWORD.encode()).digest()
    body = json.dumps({
        "EncryptType": "MD5", "LoginType": "DVRIP-Android",
        "PassWord": PASSWORD, "UserName": USERNAME,
    }).encode()
    cipher = AES.new(key, AES.MODE_ECB)
    ct = cipher.encrypt(pkcs7_pad(body))
    return base64.b64encode(ct) + b"\x00"


# --- Hypothesis 4: RSA-encrypt a random AES key with the server's public key,
#     prepend it (base64) to the AES-ECB-encrypted JSON body, colon-separated ---
def hyp4(rsa_key):
    aes_key = os.urandom(16)
    cipher_rsa = PKCS1_v1_5.new(rsa_key)
    enc_key = cipher_rsa.encrypt(aes_key)
    body = json.dumps(legacy_login_json()).encode()
    cipher = AES.new(aes_key, AES.MODE_ECB)
    ct = cipher.encrypt(pkcs7_pad(body))
    combined = base64.b64encode(enc_key) + b":" + base64.b64encode(ct)
    return combined + b"\x00"


# --- Hypothesis 5: AES key = MD5(username + password) ---
def hyp5(rsa_key):
    key = hashlib.md5((USERNAME + PASSWORD).encode()).digest()
    body = json.dumps(legacy_login_json()).encode()
    cipher = AES.new(key, AES.MODE_ECB)
    ct = cipher.encrypt(pkcs7_pad(body))
    return base64.b64encode(ct) + b"\x00"


if __name__ == "__main__":
    hypotheses = [
        ("AES-ECB key=MD5(password), sofia-hashed password field", hyp1),
        ("AES-CBC-zeroIV key=MD5(password), sofia-hashed password field", hyp2),
        ("AES-ECB key=MD5(password), plaintext password field", hyp3),
        ("RSA-wrapped random AES key + AES-ECB body", hyp4),
        ("AES-ECB key=MD5(username+password), sofia-hashed password field", hyp5),
    ]
    for label, fn in hypotheses:
        try:
            success = try_login(label, None, fn)
            if success:
                print(f"\n*** SUCCESS: {label} ***")
                sys.exit(0)
        except Exception as e:
            print(f"  error: {e!r}")
        print()
    print("No hypothesis succeeded.")
