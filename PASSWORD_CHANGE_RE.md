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
  provisioning by the factory app with a random password we don't have
  (`xkfu`/blank = `Ret:203`).

## What this means for the app

- The change protocol is plaintext and fully known — implementable directly
  (`ModifyPassword` + `System.ExUserMap` write; `ChangeRandomUser` for the
  no-login provisioning case).
- It only takes effect for an account you're authenticated as with real creds.
  On a **fresh / factory-reset** camera (blank real admin) the
  `ModifyPassword`+`ExUserMap` path applies. On this already-provisioned test
  unit we authenticate as the backdoor `admin`, so it can't be demonstrated
  end-to-end here without a factory reset (or `xkfu`'s password).
- For securing a camera the user pairs through OUR app, the correct path is
  `ChangeRandomUser` at pairing time (we have the random creds then).
