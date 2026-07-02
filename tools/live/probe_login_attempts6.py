#!/usr/bin/env python3
"""Round 6: built from disassembling the real vendor SDK (libFunSDK.so,
extracted from the user-provided APK). Confirmed via NewLoginPTL/EncDevPassord/
RSAV15 disassembly: message 1000 is a JSON object where UserName and PassWord
(and possibly others) are individually RSA/PKCS1v1.5-encrypted then hex-encoded
(via ByteToHexStr) before being inserted as JSON string values -- not a single
AES blob. PassWord's plaintext is a base62 MD5-based hash (function XMMD5Encrypt/
MD5_8, confirmed identical to the JS main.js version), possibly salted with a
literal "QunGuang_" prefix depending on a runtime switch this script can't
determine, so both variants are tried.
"""
import socket
import struct
import sys
import json
import hashlib
import os
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

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


def rsa_hex(rsa_key, plaintext: str) -> str:
    cipher = PKCS1_v1_5.new(rsa_key)
    ct = cipher.encrypt(plaintext.encode())
    return ct.hex().upper()


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
    pw_variants = {
        "plain-md5_8": md5_8(PASSWORD),
        "QunGuang_-salted-md5_8": md5_8("QunGuang_" + PASSWORD),
    }
    print("password hash variants:", pw_variants)

    for pw_label, pw_hash in pw_variants.items():
        s, rsa_key = negotiate()
        user_hex = rsa_hex(rsa_key, USERNAME)
        pass_hex = rsa_hex(rsa_key, pw_hash)
        body = {
            "EncryptType": "MD5",
            "LoginType": "DVRIP-Web",
            "UserName": user_hex,
            "PassWord": pass_hex,
        }
        payload = (json.dumps(body) + "\n").encode() + b"\x00"
        s.sendall(make_frame(0, 1, 1000, payload))
        resp = recv_frame(s)
        s.close()
        print(f"=== plaintext JSON with RSA+hex fields, pw={pw_label} ===")
        if resp:
            text = resp["payload"].rstrip(b"\x00").decode(errors="replace")
            print(f"  {text}")
            try:
                parsed = json.loads(text)
                if parsed.get("Ret") == 100:
                    print(f"\n*** SUCCESS: {pw_label} ***")
                    sys.exit(0)
            except Exception:
                pass
        else:
            print("  no response")
        print()

    print("No hypothesis in this round succeeded.")
