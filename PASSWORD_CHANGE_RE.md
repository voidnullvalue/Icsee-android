# Password change — reverse-engineering notes (libFunSDK.so, arm64-v8a)

Status: **password change not yet cracked.** Username change works (ships in
v0.6.0). This documents what the native RE has established so far, so the work
isn't lost between sessions.

## Confirmed live (device 192.168.88.129, factory admin/no-password)

- Plaintext `ModifyPassword` (msg 1040) and `ModifyUser` (1484) Password field
  are **silently ignored**: `Ret:100` but the stored `Password` hash never
  changes (snapshotted before/after). Tested 4 hash encodings (SofiaHash-8,
  mod62-16, MD5-hex lower/upper) — all no-op. So it is NOT an encoding problem;
  the plaintext channel is not privileged to change passwords.
- `ModifyUser` **rename** (Name field) DOES apply — so account writes work in
  general; password specifically is gated.
- Login validates against the 8-char SofiaHash (`Password` field = `tlJwpbo6`
  for the empty password), NOT `PasswordV2`.

## From libFunSDK.so (symbols intact — XM code is not stripped)

Key crypto primitives (verified by disassembly):

- `XAES::sha1prng_key(seed, out)` = **SHA1(SHA1(seed))[0:16]** — the classic
  Android `SecureRandom("SHA1PRNG").setSeed(seed)` → AES-128 key derivation.
- `XAES::AES_ECB_Encrypt128(data, len, keyOrSeed, out, useRawKey)`:
  `useRawKey==0` → key = `sha1prng_key(seed)`; `useRawKey==1` → key = raw 16
  bytes. AES-128-ECB.
- `XAES::AES_ECB_Encrypt128_FixedKey_Base64(data, len, key, outString)` — ECB
  encrypt then base64. NOTE "FixedKey" means *the caller supplies the key*, and
  different callers supply different keys (e.g. the `CDataCenter` local-password
  path derives its key from a reversed device string via `StrReverseOrder`),
  so there is not one single global constant to lift.
- `GetRandomAesKey(out, randomA, randomB)` — builds a session AES key from the
  handshake random strings (`GetDataBetween2Char` + `sscanf("%x")`). This is the
  secure-comm session key path.
- Login secure path: `CProtocolNetIP::NewLoginPTL[... AESEncrypt, RandomStrLen,
  PwdAppendRandomStr, TokenAppendRandomStr ...]` and DH exchange
  (`CDevProtocol::Get/SetDHParame_RandomStrA/B`). Commands can carry a plaintext
  `"DHParameter":{"RandomStrA":"..."}` block (seen baked into an OPMonitor
  template in the binary).

## Working hypothesis

`PasswordV2` is **device-computed** from the plaintext password submitted over
the *secure* channel — the client does not build a `PasswordV2` blob itself
(the literal string "PasswordV2" is absent from the .so). So changing the
password requires:

1. RSA negotiation (msg 1010/1011) — **already implemented + live-confirmed** in
   `crypto/PreLoginNegotiation` (1024-bit RSA, AES-128, real `NotEncryptMsgID`).
2. A secure login (msg 1000, `AESEncrypt=1`): client generates a 16-byte AES
   session key, RSA-wraps it with the device public key, password possibly
   salted with the handshake RandomStr (`PwdAppendRandomStr`). **Not yet
   reverse-engineered.**
3. `ModifyPassword` sent over that AES session — should then be honored.

The app's `AesSessionCrypto` exists but its transform/IV (CBC vs ECB) is still
unverified against real traffic.

## Fastest unlock (recommended)

Static RE of the secure-login construction is a long slog. A **Frida hook on
the factory app** during a real password change — hooking `XMAccount_AesEncrypt`,
`sha1prng_key`, the AES funcs, and `DevCmdGeneral` — would dump the plaintext
JSON + the AES session key at the JNI boundary in minutes, confirming the exact
wire format and key derivation. Port to Kotlin from there. A pcap helps confirm
the flow and the plaintext RandomStr but cannot decrypt the AES bodies (session
key is RSA-wrapped on the wire).
