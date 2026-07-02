#!/usr/bin/env python3
"""Live protocol probe: connect to the target camera and send message 1010
(pre-login negotiate) to observe its real request/response shape. Read-only:
no credentials, no PTZ, no media claim. Safe to run repeatedly.
"""
import socket
import struct
import sys
import json

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
    return {"magic": magic, "type": typ, "session": session, "seq": seq, "msg_id": msg_id, "payload": payload}


def try_request(label, payload_json):
    print(f"=== {label} ===")
    payload = (json.dumps(payload_json) + "\n").encode() + b"\x00" if payload_json is not None else b""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.settimeout(5)
        s.connect((HOST, PORT))
        frame = make_frame(0, 0, 1010, payload)
        print("sent bytes:", frame.hex())
        s.sendall(frame)
        resp = recv_frame(s)
        if resp is None:
            print("no response / connection closed")
        else:
            print(f"response: session=0x{resp['session']:08X} seq={resp['seq']} msg_id={resp['msg_id']} len={len(resp['payload'])}")
            print("payload bytes:", resp["payload"][:500])
            try:
                print("payload as text:", resp["payload"].decode("utf-8", errors="replace"))
            except Exception:
                pass
    except Exception as e:
        print("error:", repr(e))
    finally:
        s.close()
    print()


if __name__ == "__main__":
    try_request("1010 with empty object payload", {})
    try_request("1010 with no payload at all", None)
