#!/usr/bin/env python3
"""Probe the camera's SD-card recorded-video surface against the live device.

Reproduces the app's DVRIP requests 1:1 (DvripConfigChannel.getInfo,
DeviceManagementViewModel.refreshTime/queryRecordings) plus the calendar query,
so we can confirm the recorded-video protocol end to end before wiring playback.

Env: ICSEE_TEST_HOST/USERNAME/PASSWORD.
"""
import hashlib
import json
import os
import socket
import struct
import sys

HOST = os.environ.get("ICSEE_TEST_HOST", "192.168.88.129")
PORT = int(os.environ.get("ICSEE_TEST_PORT", "34567"))
USER = os.environ["ICSEE_TEST_USERNAME"]
PW = os.environ["ICSEE_TEST_PASSWORD"]

LOGIN = 1000
INFO_GET, INFO_RESP = 1020, 1021
FILE_QUERY, FILE_RESP = 1440, 1441
CALENDAR, CALENDAR_RESP = 1446, 1447
TIME_QUERY, TIME_RESP = 1452, 1453

_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def sofia(pw):
    d = hashlib.md5(pw.encode()).digest()
    return "".join(_CHARS[(d[2 * i] + d[2 * i + 1]) % 62] for i in range(8))


def frame(session, seq, mid, payload):
    return struct.pack("<BBHIIHHI", 0xFF, 1, 0, session, seq, 0, mid, len(payload)) + payload


def recv(sock, timeout=8):
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
    _m, _t, _r, sess, seq, _r2, mid, plen = struct.unpack("<BBHIIHHI", hdr)
    body = b""
    while len(body) < plen:
        c = sock.recv(min(65536, plen - len(body)))
        if not c:
            break
        body += c
    txt = body.rstrip(b"\x00").decode("utf-8", "replace")
    try:
        j = json.loads(txt)
    except Exception:
        j = None
    return {"mid": mid, "text": txt, "json": j}


def recv_until(sock, want, maxn=40):
    for _ in range(maxn):
        f = recv(sock)
        if f is None:
            return None
        if f["mid"] == want:
            return f
    return None


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.settimeout(8)
        self.s.connect((HOST, PORT))
        self.seq = 0
        self.sid = 0

    def login(self):
        body = {"EncryptType": "MD5", "LoginType": "DVRIP-Web", "PassWord": sofia(PW), "UserName": USER}
        self.s.sendall(frame(0, 0, LOGIN, (json.dumps(body) + "\n").encode() + b"\x00"))
        r = recv(self.s)
        if not r or not r["json"] or r["json"].get("Ret") != 100:
            raise SystemExit(f"login failed: {r and r['text']}")
        self.sid = int(r["json"]["SessionID"], 16)
        self.seq = 1
        return r["json"]

    def sid_hex(self):
        return "0x%08x" % self.sid

    def cmd(self, mid, resp_mid, obj):
        payload = (json.dumps(obj) + "\n").encode() + b"\x00"
        self.s.sendall(frame(self.sid, self.seq, mid, payload))
        self.seq += 1
        return recv_until(self.s, resp_mid)

    def close(self):
        self.s.close()


def main():
    c = Conn()
    login = c.login()
    print(f"[login]   Ret:100 session {login['SessionID']}  ({login.get('DeviceType ','?')})")

    # device time
    r = c.cmd(TIME_QUERY, TIME_RESP, {"Name": "OPTimeQuery", "SessionID": c.sid_hex()})
    dev_time = (r and r["json"] or {}).get("OPTimeQuery", "?")
    print(f"[time]    device time: {dev_time}")

    # storage info
    r = c.cmd(INFO_GET, INFO_RESP, {"Name": "StorageInfo", "StorageInfo": {}, "SessionID": c.sid_hex()})
    print(f"[storage] Ret:{(r and r['json'] or {}).get('Ret')}")
    if r and r["json"]:
        si = r["json"].get("StorageInfo")
        print("  " + json.dumps(si)[:600])

    # calendar: how many recorded files per day in a month (Xiongmai OPQueryCalendar)
    day = (dev_time.split(" ")[0] if isinstance(dev_time, str) and " " in dev_time else "2026-07-08")
    month = day.rsplit("-", 1)[0] + "-01"
    r = c.cmd(CALENDAR, CALENDAR_RESP,
              {"Name": "OPQueryCalendar",
               "OPQueryCalendar": {"BeginTime": f"{month} 00:00:00", "Channel": 0, "StreamType": "Main", "Type": "h264"},
               "SessionID": c.sid_hex()})
    print(f"[calendar] Ret:{(r and r['json'] or {}).get('Ret') if r else 'no-resp'}")
    if r and r["json"]:
        print("  " + json.dumps(r["json"].get("OPQueryCalendar", r["json"]))[:400])

    # file listing for the device's current day. NOTE: real XM clients include
    # "Event":"*"; omitting it returns Ret:119. The list can also arrive across
    # several 1441 frames, so collect until we see the array or a short timeout.
    for typ in ("h264", "h265"):
        obj = {"Name": "OPFileQuery",
               "OPFileQuery": {"BeginTime": f"{day} 00:00:00", "EndTime": f"{day} 23:59:59",
                               "Channel": 0, "DriverTypeMask": "0x0000FFFF", "Event": "*",
                               "Type": typ, "StreamType": "0x00000000"},
               "SessionID": c.sid_hex()}
        payload = (json.dumps(obj) + "\n").encode() + b"\x00"
        c.s.sendall(frame(c.sid, c.seq, FILE_QUERY, payload)); c.seq += 1
        files, ret = [], None
        for _ in range(30):
            f = recv(c.s, timeout=6)
            if f is None:
                break
            if f["mid"] != FILE_RESP:
                continue
            if f["json"]:
                ret = f["json"].get("Ret", ret)
                arr = f["json"].get("OPFileQuery")
                if isinstance(arr, list):
                    files.extend(arr)
                # Ret 100 with the array usually terminates; keep reading a beat otherwise.
                if isinstance(arr, list):
                    break
        print(f"[files]   {day} type={typ}  Ret:{ret}  {len(files)} clip(s)")
        for fi in files[:8]:
            print(f"    {fi.get('BeginTime')} -> {fi.get('EndTime')}  len={fi.get('FileLength')}  {fi.get('FileName')}")
        if len(files) > 8:
            print(f"    ... and {len(files)-8} more")
        if files:
            break

    c.close()


if __name__ == "__main__":
    main()
