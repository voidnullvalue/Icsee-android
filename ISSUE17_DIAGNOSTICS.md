# Issue #17 RTSP diagnostic build

This branch intentionally enables extremely verbose RTSP logging.

## Reproduce

1. Install the CI debug APK from this branch.
2. Start logcat before opening the camera.
3. Try the failing stream once in FHD and once in HD.
4. Let each attempt reach its terminal error.

Useful capture command:

```sh
adb logcat -c
adb logcat -v threadtime ICSeeRTSP:D RtspClient:D ExoPlayerImpl:D *:S
```

If that filter hides a useful Media3 exception, capture the full app process instead:

```sh
adb logcat -c
adb logcat -v threadtime --pid="$(adb shell pidof -s com.voidnullvalue.icseelocal)"
```

## Privacy warning

The app's own `ICSeeRTSP` messages redact the RTSP password.

Media3's native `RtspClient` debug logging does **not** guarantee redaction and may print the
vendor RTSP URL verbatim, including credentials embedded in
`/user=...&password=...`.

Do not post raw diagnostic logs publicly until the RTSP username/password have been removed.

## What the log should show

The useful sequence is:

- selected credential source (configured vs factory)
- selected stream (main/FHD vs sub/HD)
- selected transport (TCP vs UDP)
- exact Media3 OPTIONS / DESCRIBE / SETUP / PLAY exchange
- server response headers, including `WWW-Authenticate`
- playback exception code and full nested cause chain
- retry-policy choice
- reconnect delay and attempt number
