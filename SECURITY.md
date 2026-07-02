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

## Cryptography

- No custom RSA or AES implementations. `crypto/DvripRsaPublicKey.kt` and
  `crypto/AesSessionCrypto.kt` use only `java.security`/`javax.crypto`
  (`KeyFactory`, `Cipher`).
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
