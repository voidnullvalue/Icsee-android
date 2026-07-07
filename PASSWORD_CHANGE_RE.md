# Password / username change — SOLVED, live-verified working

The factory app's credential-change mechanism, fully recovered from the
decompiled Java (`DevPsdManageActivity`, `SetDevPsdActivity`) and verified
against the live camera. **It is entirely plaintext — no AES secure channel,
no Frida, no RSA.** (An earlier note here hypothesised an AES secure channel;
that was wrong.)

## The working mechanism: `ChangeRandomUser`

**`ChangeRandomUser`** (msg 1660/1661, **NO login, no `SessionID`** —
`FunSDK.DevConfigJsonNotLoginPtl` in the vendor source) is a session-less
command sent over a throwaway connection:

```json
{"Name":"ChangeRandomUser","ChangeRandomUser":{
   "RandomName":"<current username>","RandomPwd":"<current password>",
   "NewName":"<new username>","NewPwd":"<new password>"}}
```

Everything plaintext. **Live-verified working 2026-07-07** against the real
per-device account (`xkfu`, not the `admin` backdoor) on an already-provisioned
camera — not just the fresh-from-BLE-pairing case it was originally built for:

```
Before: xkfu / ym4unt  (Ret:100)
ChangeRandomUser{RandomName:xkfu, RandomPwd:ym4unt, NewName:xkfu, NewPwd:cru1234} -> Ret:100
After:  xkfu / cru1234 (Ret:100),  xkfu / ym4unt (Ret:203 -- old password correctly rejected)
```

Implemented in `ChangeRandomUserClient.kt` and wired into
`DeviceManagementViewModel.changePassword`. Because it doesn't touch the
shared session at all, it doesn't count against the per-camera Ret:205
login-rate budget either (see PROTOCOL_STATUS.md).

## Two other mechanisms exist in the vendor source but do NOT work

Both were tried first (they looked like the "proper" persistent-storage path)
and both confirmed broken against a real account:

1. **`ModifyPassword`** (config-set, msg 1040) — updates the legacy SofiaHash
   login store:
   ```json
   {"Name":"ModifyPassword","ModifyPassword":{
      "EncryptType":"MD5","UserName":"<user>",
      "PassWord":"<SofiaHash(oldpw)>","NewPassWord":"<SofiaHash(newpw)>"},
    "SessionID":"0x..."}
   ```
   ACKs `Ret:100` but does not change what login actually checks.

2. **`System.ExUserMap`** (config-get 1042 / config-set 1040) read-modify-write,
   following `DevPsdManageActivity`'s exact flow: after `ModifyPassword`, read
   `System.ExUserMap`, set the matching user's `Password` to
   `u(cachedNewPassword)`, write back:
   ```json
   {"Name":"System.ExUserMap","System.ExUserMap":{
      "User":[{"Name":"<user>","Password":"<u(newpw)>"}],"UserNum":N},
    "SessionID":"0x..."}
   ```
   `u(pw)` (`o3.f.u` / `AbstractC4571f.u` in the decompiled source, confirmed
   byte-for-byte against the vendor smali): `u("")=""`; else
   `"0001" + base64(pw)` with the first two base64 chars swapped, e.g.
   `u("test1234")="0001GdVzdDEyMzQ="`.

   **Live-tested 2026-07-07 against `xkfu` with a real, valid session — does
   NOT produce a working login**, despite the formula matching the vendor
   source exactly. Four `Password`-field formats were tried:

   | `Password` field content | `PasswordV2` regenerated? | New password logs in? |
   |---|---|---|
   | `u(newpw)` (the vendor formula, confirmed correct) | No | No (`Ret:203`) |
   | same, `PasswordV2` field omitted from the write | No | No |
   | raw plaintext `newpw` | **Yes** (blob changed) | No (`Ret:203`) |
   | `SofiaHash(newpw)` (what login itself sends) | No | No |

   Only the raw-plaintext attempt even got the device to acknowledge the
   change by regenerating `PasswordV2` at all, and even that didn't produce a
   working login. Whatever the device derives `PasswordV2` from, it isn't the
   plaintext, the SofiaHash, or the `u()` obfuscation the vendor app itself
   sends there — possibly this call site in `DevPsdManageActivity` exists for
   a different purpose than we assumed (re-syncing the format of an
   *unchanged* password after some other operation, not applying a new one),
   or there's additional client-side state this app doesn't replicate.
   **Old password is unaffected in every case tested** — the app verifies by
   re-login before persisting, so nothing was lost testing this. Also notable:
   the write may itself be rate/anti-thrash limited like login (`Ret:205`) —
   only 1 of 4 rapid back-to-back attempts produced any visible change at all.

Neither of these is used by the app. `ChangeRandomUser` above is the one and
only mechanism wired into `changePassword`.

## Username change

**`ModifyUser`** (msg 1484) — live-confirmed working (rename applies,
re-login under the new name succeeds). Independent of the password mechanism
above; see `DeviceManagementViewModel.changeUsername`.

## Live-verified facts about the backdoor

- **`admin`/blank is a hardcoded factory-test backdoor** (Memo "factory test
  account"). Every credential change to `admin` returns `Ret:100` yet `admin`
  keeps authenticating as blank — the stored password is ignored for auth. So
  it cannot be secured by *any* mechanism, including the working
  `ChangeRandomUser` one above (not tested against `admin` specifically, but
  expected to be equally moot given the backdoor ignores stored credentials
  entirely).
- The **real** admin account is **`xkfu`** (Memo "admin 's account"). Its
  plaintext password is recoverable via `GetRandomUser` + AES-128-CBC
  decryption keyed from the device's own serial number — see
  PROTOCOL_NOTES.md "Recovering the real provisioned account" and
  `[[project-icsee-random-user-decryption]]` — and, per this file, can now
  also be *changed* via `ChangeRandomUser`. Together these mean `xkfu` is a
  fully manageable account: recoverable and changeable, independent of the
  unremovable `admin` backdoor.
