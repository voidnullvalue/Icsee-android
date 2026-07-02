#!/usr/bin/env python3
"""Round 5: use the camera's own real password-hash function, extracted
from its web UI's main.js (function MD5_8: MD5 the password, then for each
of 8 byte-pairs, sum mod 62 and map to '0-9A-Za-z') instead of the guessed
mod-32 "sofia hash". This is not a guess -- it's copied from the camera's
own served JavaScript.
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


def md5_8(s: str) -> str:
    """Copied from the camera's own js/main.js MD5_8() function."""
    h = hashlib.md5(s.encode()).hexdigest()
    out_bytes = [int(h[i:i + 2], 16) for i in range(0, 32, 2)]
    out = []
    for i in range(8):
        v = (out_bytes[2 * i] + out_bytes[2 * i + 1]) % 62
        if 0 <= v <= 9:
            out.append(chr(v + 48))
        elif 10 <= v <= 35:
            out.append(chr(v + 55))
        else:
            out.append(chr(v + 61))
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


def report(label, payload_bytes):
    print(f"=== {label} ===")
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
    hashed_pw = md5_8(PASSWORD)
    print(f"MD5_8(password) = {hashed_pw}")

    bodies = [
        ("EncryptType MD5, LoginType DVRIP-Web, PassWord=MD5_8(pw)", {"EncryptType": "MD5", "LoginType": "DVRIP-Web", "PassWord": hashed_pw, "UserName": USERNAME}),
        ("bare UserName/PassWord=MD5_8(pw)", {"UserName": USERNAME, "PassWord": hashed_pw}),
    ]

    key_candidates = {
        "MD5(password)": hashlib.md5(PASSWORD.encode()).digest(),
        "MD5(MD5_8(password))": hashlib.md5(hashed_pw.encode()).digest(),
        "MD5(username+password)": hashlib.md5((USERNAME + PASSWORD).encode()).digest(),
    }

    for label, body in bodies:
        json_bytes = json.dumps(body).encode()
        for key_label, key in key_candidates.items():
            for mode_name in ["ECB", "CBC-zeroIV"]:
                cipher = AES.new(key, AES.MODE_ECB) if mode_name == "ECB" else AES.new(key, AES.MODE_CBC, iv=b"\x00" * 16)
                ct = cipher.encrypt(pkcs7_pad(json_bytes))
                payload = base64.b64encode(ct) + b"\x00"
                result = report(f"{label} | key={key_label} | {mode_name}", payload)
                if result and result.get("Ret") == 100:
                    print(f"\n*** SUCCESS ***")
                    sys.exit(0)

    # Also try: RSA-encrypt(MD5_8(password)) directly as a compact credential blob (no JSON, no AES)
    s, rsa_key = negotiate()
    cipher_rsa = PKCS1_v1_5.new(rsa_key)
    for candidate in [f"{USERNAME}\n{hashed_pw}", hashed_pw, json.dumps({"UserName": USERNAME, "PassWord": hashed_pw})]:
        s2, rsa_key2 = negotiate()
        ct = PKCS1_v1_5.new(rsa_key2).encrypt(candidate.encode())
        payload = base64.b64encode(ct) + b"\x00"
        result = report(f"pure RSA of {candidate!r}", payload)
        if result and result.get("Ret") == 100:
            print("\n*** SUCCESS ***")
            sys.exit(0)

    print("\nNo hypothesis in this round succeeded.")
