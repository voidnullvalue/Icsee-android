# Password / username change — SOLVED via decompilation

The factory app's credential-change mechanism, fully recovered from the
decompiled Java (`DevPsdManageActivity`, `SetDevPsdActivity`) and verified
against the live camera. **It is entirely plaintext — no AES secure channel,
no Frida, no RSA.** (An earlier note here hypothesised an AES secure channel;
that was wrong.)

## The three primitives

1. **`ModifyPassword`** (config-set, msg 1040) — updates the SofiaHash login
   store:
   ```json
   {"Name":"ModifyPassword","ModifyPassword":{
      "EncryptType":"MD5","UserName":"<user>",
      "PassWord":"<SofiaHash(oldpw)>","NewPassWord":"<SofiaHash(newpw)>"},
    "SessionID":"0x..."}
   ```
   (`DevMD5Encrypt` == our `SofiaHash`.)

2. **`System.ExUserMap`** (config-get 1042 / config-set 1040) — the newer
   password store. After `ModifyPassword`, the app reads `System.ExUserMap`,
   sets the matching user's `Password` field, and writes it back:
   ```json
   {"Name":"System.ExUserMap","System.ExUserMap":{
      "User":[{"Name":"<user>","Password":"<u(newpw)>"}],"UserNum":N},
    "SessionID":"0x..."}
   ```
   where **`u(pw)`** (`AbstractC4571f.u`) is NOT encryption, just obfuscation:
   ```
   u("")  = ""
   u(pw)  = "0001" + base64(pw) with the first two base64 chars swapped
   e.g. u("test1234") = "0001GdVzdDEyMzQ="
   ```
   The device regenerates its own `PasswordV2` (16-byte AES blob) from this.

3. **`ChangeRandomUser`** (msg 1660, **NO login** — `DevConfigJsonNotLoginPtl`)
   — sets initial creds on a freshly-provisioned camera whose random creds you
   know (the BLE-pairing path). `NewPwd`/`NewName` are sent **plaintext**:
   ```json
   {"Name":"ChangeRandomUser","ChangeRandomUser":{
      "RandomName":"<cur>","RandomPwd":"<cur>","NewName":"<new>","NewPwd":"<new>"}}
   ```
   Our `ChangeRandomUserClient` already implements this.

## Live-verified facts about THIS test camera (192.168.88.129)

- `System.ExUserMap` is readable/writable in plaintext; writing a user's
  `Password` makes the device generate a `PasswordV2` for it (confirmed:
  writing `u("test1234")` for admin produced `PasswordV2:"Cb0HPmw2VhI/..."`).
- **BUT `admin`/blank is a hardcoded factory-test backdoor.** Its Memo is
  "factory test account". Every credential change to `admin` returns `Ret:100`
  yet `admin` keeps authenticating as blank — the stored password is ignored
  for auth. So it cannot be secured.
- The **real** admin account is **`xkfu`** (Memo "admin 's account"), created at
  provisioning by the factory app (`xkfu`/blank = `Ret:203`). Its plaintext
  password **is** recoverable — not random-and-lost as originally thought — via
  `GetRandomUser` + AES-128-CBC decryption keyed from the device's own serial
  number; see PROTOCOL_NOTES.md "Recovering the real provisioned account" and
  `[[project-icsee-random-user-decryption]]`. This doesn't change the
  conclusion below (the `admin` backdoor makes securing `xkfu` moot either
  way), but it does mean an app — or anyone with LAN access, since `admin`/blank
  gets you the login needed to ask — can read the "real" account's password on
  demand, not just during the original provisioning window.

## What this means for the app

- The change protocol is plaintext and fully known, and **both steps are now
  implemented** in `DeviceManagementViewModel.changePassword`
  (`ModifyPassword` + the `System.ExUserMap` read-modify-write with `u()`).
  An earlier version of that function only sent `ModifyPassword` -- written
  before this file's `ExUserMap`/`u()` findings existed -- which reliably
  failed to change login on any account whose password is checked against
  `PasswordV2` (i.e. effectively all of them). Not yet live-verified against
  hardware as of this writing.
- It only takes effect for an account you're authenticated as with real creds.
  Authenticating as the `admin` backdoor and changing *its* password is a
  no-op regardless of which mechanism is used (see SECURITY.md) -- but doing
  the same for `xkfu` (now that its real password is recoverable, see above)
  should actually take effect, since `xkfu` isn't the hardcoded-bypass
  account.
- For securing a camera the user pairs through OUR app, the correct path is
  `ChangeRandomUser` at pairing time (we have the random creds then).
