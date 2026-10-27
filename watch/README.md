# KernelSU Watch 5 adaptation

Baseline: official KernelSU **v3.3.1** (imported in commit `96a7548`).  This
branch is intentionally separate from the `ghostlock` exploit tree and from the
backslashxx `KernelSU-bs` fork.

## Target

- Device: Xiaomi Watch 5 `grass` / M2501W1
- Userspace: **armeabi-v7a** (32-bit)
- Kernel: **arm64** `5.15.137-android14-11-maybe-dirty`
- Existing lab bootstrap: W1 -> SELinux permissive -> mqsas -> root agent
  (not part of this repository)

The goal is a normal official KernelSU manager experience on the watch: status
card, `ksu` root shell, module and superuser screens, without the nano proxy or
`/system/bin/sh` workaround.

## Findings that drive the design

1. **Official v3.3.1 does not ship armeabi-v7a.**
   `manager/app/build.gradle.kts` lists only `arm64-v8a`, `x86_64`, `riscv64`.
   This branch adds `armeabi-v7a`, builds `ksud` for
   `armv7-linux-androideabi`, and packages it as
   `jniLibs/armeabi-v7a/libksud.so`.

2. **The injected driver fd is not inherited by `libsu` children.**
   On the live watch, the manager process (`me.weishu.kernelsu`, pid 18945) had
   `fd 66 -> anon_inode:[ksu_driver]`, but its `sh` children (pids 18987 and
   19012) had only fds 0, 1 and 2.  The official `/proc/self/fd` scan in
   `ksucalls.rs` therefore cannot see the fd in a `libsu` child.  The child then
   calls the 32-bit `reboot` syscall, which the app seccomp filter blocks.

3. **The kernel module is a separate blocker.**
   The only available `kernelsu.ko` is built for `5.15.202`; the watch kernel is
   `5.15.137`.  The lab can force-load it with a policy fixup, but it is not a
   release-quality match.  A proper `5.15.137` LKM requires the watch kernel
   source, `.config`, and `Module.symvers` (or an equivalent exact build tree).

4. **Signing is not solved by the official key.**
   The official manager signing key is not available.  A locally signed manager
   will not pass an unmodified KernelSU driver's `is_manager_apk` check.  The
   lab currently uses `blob1_nosig.ko` (is_manager_apk -> true) as a bridge.

## Design: manager-side driver-fd broker

Keep official `ksud` semantics; do not replace the root shell with the agent.

1. The manager process already receives the real `[ksu_driver]` fd from the
   watcher (fd 66 on the live watch).
2. `DriverFdBroker` (manager, `manager/app/.../ui/util/DriverFdBroker.kt`):
   - binds abstract Unix socket `@ksu_driver_broker`
   - accepts only peers with the manager's own UID
   - sends a duplicate of fd 66 with `SCM_RIGHTS`
3. `ksucalls.rs` (`watch-fd-broker` feature):
   - keeps the existing `/proc/self/fd` scan
   - if no fd, asks the broker before calling `reboot`
   - keeps the reboot+SIGSYS fallback last for normal devices
4. `KsuCli.SHELL` can then stay on the official path
   (`libksud.so debug su`), and `libsu` sees a real root shell.  No nano proxy
   and no `/system/bin/sh` bind.

This is a 32-bit userspace adaptation, not a kernel change.  It does not make
KernelSU persistent across reboots; the existing W1/mqsas bootstrap remains the
per-boot prerequisite.

## Status (2026-10-27)

- **M0** baseline import - done.
- **M1** armv7 build - done:
  - `cargo ndk -t armeabi-v7a -P 31 build --release --features watch-fd-broker`
  - manager APK built with `-PKSU_WATCH_ONLY=true`.
- **M2** fd broker - implemented in Kotlin and Rust; compiles and passes clippy.
- **M3** on-watch integration test - **not yet installed**; see the signing
  caveat below.
- **M4** real `5.15.137` LKM - blocked on kernel source/config/Module.symvers.
- **M5** signing decision - pending.

## Build (Windows, verified)

Toolchain used:

- JDK 21
- Android SDK platform `android-37.0`, build-tools `37.0.0`
- NDK `29.0.14206865`
- Gradle `9.7.1`
- Rust stable host `x86_64-pc-windows-gnu` with MinGW GCC
- `cargo-ndk 4.1.2`
- `LIBCLANG_PATH` pointing at the NDK's `libclang.dll`

The upstream checkout uses symlinks for `uapi`; a Windows copy may lose them.
Create local junctions before building:

```bat
cmd /c mklink /J manager\app\src\main\cpp\uapi uapi
cmd /c mklink /J kernel\include\uapi uapi
```

Build `ksud`:

```powershell
$env:ANDROID_NDK_HOME = "C:\Android\ndk\29.0.14206865"
$env:LIBCLANG_PATH = "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin"
Set-Location userspace/ksud
cargo ndk -t armeabi-v7a -P 31 build --release --features watch-fd-broker
Copy-Item target/armv7-linux-androideabi/release/ksud ../../manager/app/src/main/jniLibs/armeabi-v7a/libksud.so
```

Build the manager:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
$env:ANDROID_HOME = "C:\Android"
Set-Location manager
./gradlew :app:assembleDebug -PKSU_WATCH_ONLY=true
```

Artifacts from the verified build:

- `manager/app/build/outputs/apk/debug/KernelSU_96a7548_30001-debug.apk`
  (30,944,266 bytes)
- APK contains only `lib/armeabi-v7a/`:
  `libadbroot.so`, `libkernelsu.so`, `libksud.so`
- APK signer: local Android debug key
  `3BF1577C690A4A57534AFE7338B9534FDBA659DEEDCE1E6F3ED408AE750545DC`

Checks run:

```text
cargo ndk -t armeabi-v7a -P 31 check --features watch-fd-broker   PASS
cargo ndk -t armeabi-v7a -P 31 clippy --features watch-fd-broker  PASS
cargo ndk -t arm64-v8a -P 31 clippy                               PASS
cargo fmt --check                                                  PASS
:app:assembleDebug -PKSU_WATCH_ONLY=true                           PASS
```

Smoke test on the watch (binary pushed to `/data/local/tmp`, then removed):

```text
ksud 96a7548 (uapi: 5)
version: 32680
flags: 0x5
uapi_version: 5
features: 0x7
lkm: true
late_load: true
```

## Install caveat

The APK is signed with the local Android debug key.  The currently installed
manager is signed with a different debug key
(`4359C171F32543394CBC23EF908C4BB94CAD7C8087002BA164C8230948C21549`).
`adb install -r` will fail with a signature mismatch; an uninstall/reinstall is
required, which may change UID 10096.  If the UID changes, the driver's manager
appid/crowning must be redone, and the watcher must inject the new process.

Do not replace the working manager on the current boot without a rollback plan.

## Non-goals

- No changes to W1/W1b/W2/W3
- No changes to the mqsas bootstrap
- No attempt to bypass AVB/bootloader or make KernelSU persistent
- No release of the nosig LKM/debug APK as a supported product

## Open items

- Watch kernel source, `.config` and `Module.symvers` for `5.15.137`
- Decision on the manager signer and the corresponding LKM trust policy
- On-device confirmation that `LocalServerSocket(String)` uses the abstract
  namespace expected by the Rust client
- Whether to keep `targetSdk 37` or lower to 34/36 for the watch
