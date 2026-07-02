"""Shared DVRIP framing helpers for the pcap analysis tools.

Header layout (20 bytes, little-endian), per the Perl reference:
    pack('CCxxVVxxvV', 0xff, 0, $session, $seq, $msg_id, length($payload))

Offset  Size  Field
0       1     Magic = 0xFF
1       1     Type/reserved
2       2     Reserved
4       4     Session ID, little-endian
8       4     Sequence, little-endian
12      2     Reserved
14      2     Message ID, little-endian
16      4     Payload length, little-endian
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field

MAGIC = 0xFF
HEADER_LEN = 20
HEADER_STRUCT = struct.Struct("<BBHIIHHI")  # magic,type,reserved,session,seq,reserved2,msgid,paylen

# Sanity bound so a corrupt/garbage header never triggers a huge allocation
# when reassembling frames from a live TCP stream.
MAX_PAYLOAD_LEN = 16 * 1024 * 1024


@dataclass
class DvripFrame:
    magic: int
    type_byte: int
    session: int
    seq: int
    msg_id: int
    payload_len: int
    payload: bytes
    stream_offset: int
    raw_header: bytes = field(repr=False)

    @property
    def session_hex(self) -> str:
        return f"0x{self.session:08X}"


class FrameParseError(Exception):
    pass


def iter_frames(data: bytes, *, strict: bool = False):
    """Parse consecutive DVRIP frames out of a reassembled byte stream.

    Yields DvripFrame for each complete frame found. If strict is False
    (the default, used for noisy pcap-derived streams), a bad magic byte
    causes a 1-byte resync scan rather than raising, since encrypted
    payloads or stream misalignment from capture gaps can otherwise abort
    an entire analysis run.
    """
    offset = 0
    n = len(data)
    while offset < n:
        if n - offset < HEADER_LEN:
            return
        magic = data[offset]
        if magic != MAGIC:
            if strict:
                raise FrameParseError(f"bad magic 0x{magic:02x} at offset {offset}")
            offset += 1
            continue
        header = data[offset:offset + HEADER_LEN]
        magic_b, type_byte, _reserved, session, seq, _reserved2, msg_id, paylen = HEADER_STRUCT.unpack(header)
        if paylen > MAX_PAYLOAD_LEN:
            if strict:
                raise FrameParseError(f"payload length {paylen} exceeds sanity bound at offset {offset}")
            offset += 1
            continue
        body_start = offset + HEADER_LEN
        body_end = body_start + paylen
        if body_end > n:
            # Incomplete frame at the tail of the captured stream.
            return
        payload = data[body_start:body_end]
        yield DvripFrame(
            magic=magic_b,
            type_byte=type_byte,
            session=session,
            seq=seq,
            msg_id=msg_id,
            payload_len=paylen,
            payload=payload,
            stream_offset=offset,
            raw_header=header,
        )
        offset = body_end


def encode_frame(session: int, seq: int, msg_id: int, payload: bytes, type_byte: int = 1) -> bytes:
    header = HEADER_STRUCT.pack(MAGIC, type_byte, 0, session, seq, 0, msg_id, len(payload))
    return header + payload


def looks_like_json(payload: bytes) -> bool:
    stripped = payload.rstrip(b"\x00\n\r ")
    return stripped.startswith(b"{") and stripped.endswith(b"}")
