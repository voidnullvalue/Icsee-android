#!/usr/bin/env python3
"""Round 4: structural clue from the real captured message-1000 ciphertext
(432 bytes = 128 + 304; 304 is itself 19 AES blocks) -- test "RSA-encrypted
random AES-128 key (128 bytes) directly concatenated with the AES-CBC/ECB
-encrypted JSON body, whole thing base64-wrapped" as a single blob, no
separator.
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
    s.sendall(make_frame(0, 0, 1010, (json.dumps({}) + "\n").encode() + b"\x00"))
    resp = recv_frame(s)
    negotiation = json.loads(resp["payload"].rstrip(b"\x00").decode())
    modulus_hex, exponent_hex = negotiation["PublicKey"].split(",")
    key = RSA.construct((int(modulus_hex, 16), int(exponent_hex, 16)))
    return s, key


def attempt(label, body_dict, aes_mode_name):
    s, rsa_key = negotiate()
    aes_key = os.urandom(16)
    rsa_cipher = PKCS1_v1_5.new(rsa_key)
    enc_key = rsa_cipher.encrypt(aes_key)  # exactly 128 bytes for RSA-1024
    body = json.dumps(body_dict).encode()
    padded = pkcs7_pad(body)
    if aes_mode_name == "ECB":
        aes_cipher = AES.new(aes_key, AES.MODE_ECB)
    else:
        aes_cipher = AES.new(aes_key, AES.MODE_CBC, iv=b"\x00" * 16)
    enc_body = aes_cipher.encrypt(padded)
    combined = enc_key + enc_body
    payload = base64.b64encode(combined) + b"\x00"
    print(f"=== {label} ({aes_mode_name}) total ciphertext={len(combined)} bytes (128 + {len(enc_body)}) ===")
    s.sendall(make_frame(0, 1, 1000, payload))
    resp = recv_frame(s)
    s.close()
    if resp is None:
        print("  no response")
        return False
    text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
    print(f"  {text}")
    try:
        parsed = json.loads(text)
        return parsed.get("Ret") == 100
    except Exception:
        return False


if __name__ == "__main__":
    bodies = [
        ("sofia-hashed, DVRIP-Web", {"EncryptType": "MD5", "LoginType": "DVRIP-Web", "PassWord": sofia_hash(PASSWORD), "UserName": USERNAME}),
        ("plaintext password, DVRIP-Web", {"EncryptType": "MD5", "LoginType": "DVRIP-Web", "PassWord": PASSWORD, "UserName": USERNAME}),
        ("bare plaintext creds", {"UserName": USERNAME, "PassWord": PASSWORD}),
    ]
    for label, body in bodies:
        for mode in ["ECB", "CBC-zeroIV"]:
            try:
                if attempt(label, body, mode):
                    print(f"\n*** SUCCESS: {label} / {mode} ***")
                    sys.exit(0)
            except Exception as e:
                print(f"  error: {e!r}")
            print()
    print("No hypothesis in this round succeeded.")
