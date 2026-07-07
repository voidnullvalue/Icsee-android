# Security

## Threat model

This app talks directly to a camera on the local network over an
unencrypted-at-the-header, weakly-encrypted-at-best-at-the-payload
proprietary protocol (DVRIP). It does not use cloud services. The realistic
threats are:

- Another device on the same LAN/Wi-Fi observing or injecting traffic.
- Malware on the phone reading app storage.
- A malicious or compromised camera (the app trusts the camera's responses
  more than a hardened client normally should, because the protocol itself
  offers little to verify against).

It is explicitly **not** designed to resist a hostile camera vendor's cloud
infrastructure, because it never talks to one.

## Device defect: unremovable blank-password `admin` backdoor

Reverse-engineering the vendor app and live-testing the target camera
established that the device has an `admin` account with a **blank password
that always authenticates over DVRIP on the LAN and cannot be secured**:

- `admin`/(no password) returns `Ret:100` in every state tested, including a
  reported factory-fresh state.
- Every credential change applied to `admin` returns `Ret:100` but does not
  change authentication -- verified live for `ModifyPassword` (msg 1040),
  `ModifyUser` (msg 1484), and a `System.ExUserMap` write (msg 1040). The
  device even generates a fresh `PasswordV2` blob from the written value, yet
  `admin`/blank still logs in. The account's own metadata labels it
  `Memo: "factory test account"`.

So **anyone with LAN access has full DVRIP control with no credentials**,
independent of configured passwords. This is classic Xiongmai default-account
behaviour (the device family behind Mirai-era compromises) and is a firmware
property, not fixable from a client.

The real administrative account is a random-named account (e.g. `xkfu`,
`Memo: "admin 's account"`) provisioned with a per-device password. **That
password is not actually secret from anyone with LAN access.** It is
recoverable at any time -- not just during the original BLE provisioning --
via the DVRIP `GetRandomUser` command: its base64 `Info`/`InfoUser` field
decrypts with AES-128-CBC (zero IV, key derived entirely from the device's own
`SystemInfo` serial number) to a plaintext `"p1:<user> p2:<pass> t:<token>"`
string. See PROTOCOL_NOTES.md "Recovering the real provisioned account" and
`[[project-icsee-random-user-decryption]]` for the full derivation.

Since reaching `GetRandomUser` only requires the already-open `admin`/blank
login, **the blank-`admin` backdoor doesn't just grant direct DVRIP control --
it also hands over the means to recover the "real" account's plaintext
password on demand**, with no cryptographic material beyond the device's own
serial number (which the camera also freely discloses to anyone logged in).
There is no privileged secret here at all: both the bypass and the "real"
credentials are available to any LAN client, indefinitely. An earlier note
here assumed the real account's password was single-use/unrecoverable once
the provisioning ACK was missed; that was wrong.

Consequences for this app:

- **Password change works and is live-verified** (2026-07-07), via
  `ChangeRandomUser` (msg 1660, a session-less command -- see
  `PASSWORD_CHANGE_RE.md`), against the real `xkfu` account on an
  already-provisioned camera: old password rejected afterward, new password
  authenticates. Two other candidate mechanisms in the vendor source
  (`ModifyPassword` alone, and `ModifyPassword` + a `System.ExUserMap`
  read-modify-write with the documented `u()` obfuscation) were tried first
  and confirmed NOT to work despite ACKing `Ret:100` -- see
  `PASSWORD_CHANGE_RE.md` for the full comparison. `changePassword` verifies
  by re-login before persisting regardless of which ACK it gets, so none of
  this testing risked the working credentials.
- **Username change** (`ModifyUser`) is live-confirmed working.
- Neither changes the blank-`admin` backdoor, which stands independent of any
  other account's password -- so this app still cannot make the device fully
  secure, but it CAN now give the user real control over the one account that
  isn't a hardcoded bypass.

### Provisioning ACK not reliably captured (no longer blocks credential display)

This app's BLE pairing does not reliably receive the provisioning ACK carrying
the camera's assigned random credentials -- the camera typically drops the BLE
link as it joins Wi-Fi, and we fall back to `admin`/no-password to reach it
over LAN. This used to mean the user never saw their real (non-backdoor)
login. It no longer does: once the camera is reachable over LAN as
`admin`/blank, the app independently queries and decrypts the real account via
`GetRandomUser` (see above) and surfaces it in the BLE pairing success screen
and via "Retrieve Credentials" in camera settings -- regardless of whether the
BLE ACK was ever captured.

## Cryptography

- No custom RSA or AES implementations. `crypto/DvripRsaPublicKey.kt`,
  `crypto/AesSessionCrypto.kt`, and `config/XiongmaiCrypto.kt` (the
  `GetRandomUser` decryption above) all use only `java.security`/`javax.crypto`
  (`KeyFactory`, `Cipher`) -- never a hand-rolled cipher.
- The camera's own negotiated cipher (RSA-1024 + AES-128, confirmed live --
  see PROTOCOL_NOTES.md) is weak by modern standards. This app does not
  choose that cipher; it's dictated by the camera's firmware. There is
  nothing this app can do to strengthen it without breaking compatibility.
- `usesCleartextTraffic="true"` is set in the manifest because DVRIP is
  plain TCP with no TLS option on the control/media/talk ports used. This
  is a deliberate, necessary choice for a LAN-only device controller, not
  an oversight -- there is no cleartext HTTP/API traffic to a backend.

## Credential storage

- Username/password are encrypted with AES-256-GCM via a key generated
  inside Android Keystore (`storage/KeystoreCipher.kt`) and never leave the
  keystore. Only ciphertext (base64) is persisted, in DataStore Preferences
  (`storage/CameraStore.kt`).
- Non-sensitive fields (host, ports, channel, stream type, display name)
  are stored in plain DataStore -- there is nothing sensitive about a LAN
  IP address or port number.
- No credentials are hardcoded anywhere in this repository. Live-test
  credentials are opt-in via `local-test.properties` (gitignored) or
  environment variables -- see TESTING.md.

## Diagnostics export

Per the task brief, diagnostics never display or export:

- plaintext passwords
- AES keys
- RSA-encrypted credential blocks
- microphone contents
- complete video frames unless explicitly requested

`diagnostics/DiagnosticsState.kt`'s `DiagnosticsSnapshot` only carries
booleans, counters, message ids, and free-text error strings -- there is no
field in that type capable of holding a credential or key even by mistake.
`BoundedHistory` caps command history at 200 entries so it can't grow
without bound over a long session, but entries are summaries
(`CommandHistoryEntry.summary: String`), not raw payload dumps.

## pcap evidence handling

- `/root/pcap.pcap` is never committed (`.gitignore`).
- Derived per-stream dumps under `tools/pcap/*.followraw.txt` (which still
  contain real, if mostly-encrypted, session traffic from the target
  camera) are also gitignored and were not committed.
- `PROTOCOL_NOTES.md` quotes specific byte sequences and JSON fragments as
  evidence, but nothing in it is a working credential -- the login payload
  quoted there is ciphertext of unknown plaintext, not a password.

## Known-weak / unresolved areas

- The exact AES key derivation for the post-login transport is unresolved
  (see PROTOCOL_STATUS.md). Until it's known, this app cannot exercise that
  code path at all, live -- there is no "insecure but working" fallback
  implemented.
- This app does not attempt certificate pinning or any kind of camera
  identity verification beyond MAC/serial matching, because the underlying
  protocol has no such concept to hook into.
