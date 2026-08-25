# AVTalk phone-to-camera broadcasting

AVTalk sends the Android device's camera and microphone **to** compatible iCSee/Xiongmai cameras that have a built-in display and speaker. This is separate from the normal RTSP live-view and OPTalk push-to-talk paths.

## Status

**Verified working on AVTalk-capable hardware.** The implementation in this repository was tested by `@jericjan` against a compatible camera after the protocol was reverse-engineered from the vendor app and reproduced in a working Python `main.py` reference implementation attached to issue #6.

The Android port now provides:

- phone camera preview with front/back lens switching;
- H.265/HEVC video uplink to the camera display;
- G.711 A-law microphone audio uplink to the camera speaker;
- the required DVRIP-Web login, OPMonitor, AVTalk Claim/Start/Stop, and keepalive flow;
- client-side preview orientation handling independent of the encoded video orientation.

Hardware testing confirmed that the video delivered to the camera is correctly oriented. A follow-up report found the Android preview itself rotated 90 degrees clockwise; that was fixed by separating the `TextureView` display transform from the sensor-relative rotation used by the `ImageReader`/HEVC encoder path.

## Protocol flow

The working implementation uses three TCP connections to DVRIP port 34567 sharing the authenticated session ID:

1. **Control** — plaintext MD5 `DVRIP-Web` login, optional/best-effort `DecoderPram` 1360 capability query, AVTalk Start/Stop 1415, and 1006 keepalive.
2. **OPMonitor** — 1413 Claim+Start to establish the live monitor state required by the tested camera.
3. **AVTalk media** — 1417 Claim followed by 1419 H.265 video and G.711 A-law audio frames.

Important details carried over from the working reference implementation:

- 1413 and 1417 claims use sequence 24;
- 1419 media frames use sequence 0;
- video uses the AVTalk `0xFC` keyframe and `0xFD` inter-frame wrappers;
- audio uses the `0xFA` wrapper with 320-byte A-law chunks at 8 kHz;
- no encrypted 1360/1415 path or 64 KiB media fragmentation is used by this implementation.

## Credit

AVTalk support exists because **@jericjan** reverse-engineered the protocol, supplied the working `main.py` reference flow, tested the Android port on real AVTalk-capable hardware, and reported the client-preview rotation bug that led to the final orientation fix.

See GitHub issue #6 and PR #8 for the development history.
