# Building in this proot/Termux environment

## Detected environment

```
CPU architecture: aarch64 (ARM64)
Kernel:           Linux ... 6.17.0-PRoot-Distro (proot, not real kernel root)
Distro:           Debian GNU/Linux 12 (bookworm), inside proot-distro
Host:              Termux on Android (this proot has direct filesystem access
                    to the Termux install at /data/data/com.termux/files/usr)
```

## Toolchain actually used

| Component | Path | Notes |
|---|---|---|
| Java | `/usr/lib/jvm/java-17-openjdk-arm64` (Debian `openjdk-17-jdk-headless`) | Native aarch64, works directly. |
| Gradle | `/data/data/com.termux/files/usr/opt/gradle/bin/gradle` (Termux package, on `PATH`) | Gradle 9.6.1, native aarch64. Used once to bootstrap the Gradle wrapper (`gradle wrapper --gradle-version 9.6.1`); the wrapper (`./gradlew`) is used for everything after that. |
| Android SDK | `/data/data/com.termux/files/home/lib/android-sdk-9123335` | Pre-existing SDK install found on this system (platforms 24/28/35, build-tools 33.0.1; AGP auto-downloaded platform 36 and build-tools 36.0.0 into it on first build via its own SDK auto-provisioning). Referenced via `local.properties` (`sdk.dir=...`), not committed. |
| Kotlin | 2.4.0, via AGP's built-in Kotlin support | AGP 9.x no longer needs/accepts the separate `org.jetbrains.kotlin.android` Gradle plugin -- applying it fails the build with an explicit error telling you to remove it. Only `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` are applied. |
| AGP | 9.2.1 | Latest stable at build time; required for Gradle 9.6.1 compatibility. |
| aapt2 | see below | The one non-trivial part of this setup. |

## The aapt2 problem, and how it's solved

Neither of the two "obvious" aapt2 binaries works natively on aarch64:

1. **Maven-distributed aapt2** (what AGP downloads by default, artifact
   `com.android.tools.build:aapt2`): only published for `linux` (x86_64),
   `osx`, and `windows` classifiers. There is no `linux-aarch64` variant.
   Confirmed by downloading `aapt2-9.2.1-...-linux.jar` and inspecting the
   embedded binary's ELF header: `Machine: Advanced Micro Devices X86-64`.
2. **Termux's own native aarch64 aapt2** (`/data/data/com.termux/files/usr/bin/aapt2`,
   reports version `2.19`): runs natively, but is too old to parse the
   resource table format used by `android-35`/`android-36`'s `android.jar`
   -- `aapt2 link` fails with `error: failed to load include path
   .../android.jar` even outside Gradle, confirmed with a bare CLI
   invocation.

**Solution: run the official x86_64 aapt2 (the exact one matching this
AGP version) under QEMU user-mode emulation.**

```bash
apt-get install -y qemu-user-static
dpkg --add-architecture amd64
apt-get update
apt-get install -y libc6:amd64 libstdc++6:amd64 zlib1g:amd64
```

The x86_64 `aapt2` binary was extracted from the Maven artifact
(`aapt2-9.2.1-15009934-linux.jar`, which is just a zip containing the
binary) into `toolchain/aapt2-bin/aapt2-x86_64`, and a wrapper script
(`toolchain/aapt2-bin/aapt2`) runs it under `qemu-x86_64-static` with
`QEMU_LD_PREFIX` pointed at the amd64 glibc just installed:

```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export QEMU_LD_PREFIX=/usr/lib/x86_64-linux-gnu
exec qemu-x86_64-static "$SCRIPT_DIR/aapt2-x86_64" "$@"
```

One non-obvious constraint: AGP validates the `android.aapt2FromMavenOverride`
path by checking that it **literally ends with the filename `aapt2`**
(`SdkConstants.FN_AAPT2`, checked via `String.endsWith`) -- a wrapper named
e.g. `aapt2-qemu-wrapper.sh` is silently rejected with "Custom AAPT2
location does not point to an AAPT2 executable". The wrapper must be named
exactly `aapt2`.

`gradle.properties` wires it in:

```properties
android.aapt2FromMavenOverride=/root/icsee-local-camera/toolchain/aapt2-bin/aapt2
```

This was verified by actually running `aapt2 link` against `android-36`'s
`android.jar` (success) versus the same call with Termux's native aapt2
(fails), before wiring it into Gradle, and then by a full `assembleDebug`
succeeding end-to-end.

## Required environment variables / files

- `local.properties` (not committed): `sdk.dir=/data/data/com.termux/files/home/lib/android-sdk-9123335`
- `gradle.properties` (committed): the `android.aapt2FromMavenOverride` line above.
- No other environment variables are required; `JAVA_HOME`/`ANDROID_HOME`
  are not set explicitly -- Gradle resolves Java from `PATH` (Debian's
  openjdk-17) and the SDK from `local.properties`.

## Exact build commands

```bash
cd /root/icsee-local-camera
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Termux-provided tools used

- `gradle` (native aarch64 Gradle distribution) -- used once to bootstrap the wrapper.
- Termux's native aapt2 was tried first and rejected (see above) but its
  presence confirmed an aarch64-native aapt2 build exists in principle; it
  just isn't new enough for this project's `compileSdk`.

## Known proot limitations encountered

- **Recursive bind mount**: `find /` from within this proot recurses into
  `/data/data/com.termux/files/usr/var/lib/proot-distro/containers/...`
  which contains what appears to be a self-referential mount of the whole
  filesystem. Broad `find /` (or any recursive traversal from `/`) should
  be avoided; scope searches to specific directories instead.
- **No real kernel root**: package installs and file ownership work
  normally inside the proot, but this is not equivalent to host root --
  there's no access to real `binfmt_misc` registration, real device nodes,
  USB, etc. This is why QEMU is invoked explicitly as a wrapper rather than
  relying on transparent binfmt-registered emulation.
- **No Android emulator / device**: all Android-framework-dependent code
  (Keystore, AudioRecord, MediaCodec, PixelCopy, MediaStore, WifiManager
  multicast lock) compiles and lints cleanly but has not been exercised at
  runtime in this environment. See PROTOCOL_STATUS.md and TESTING.md for
  exactly what that implies for each feature.
- **Network access turned out to work**: contrary to a reasonable default
  assumption, this proot's network namespace *does* reach the LAN camera at
  192.168.1.100 directly (confirmed via `ping` and a raw TCP connect to
  port 34567). This was used for the read-only live protocol probes in
  `tools/live/` (see PROTOCOL_NOTES.md), though it doesn't help with
  anything that requires the actual Android app to run (Keystore, mic,
  camera surface, etc.).
