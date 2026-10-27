# KernelSU Watch 5 adaptation

Baseline: official KernelSU **v3.3.1** (imported in commit `96a7548`).  This
branch is intentionally separate from the local exploit lab and from the
backslashxx `KernelSU-bs` fork.

Repository: `https://github.com/UmiSlat/kernelsu-watch`

## Target

- Device: Xiaomi Watch 5 `grass` / M2501W1
- Userspace: **armeabi-v7a** (32-bit)
- Kernel: **arm64** `5.15.137-android14-11-maybe-dirty`
- Bootstrapping and driver-fd injection are provided by a separate lab setup;
  they are not part of this repository.

## Package identity

- Application ID: **`io.github.umislat.kernelsu`**
- App label: **`KernelSU Watch`**
- Repository: **`kernelsu-watch`**

The reverse-DNS name is based on the GitHub account (`UmiSlat`, lowercased) and
does not collide with the official `me.weishu.kernelsu`.  The Kotlin namespace
stays `me.weishu.kernelsu`; only the installed application ID and label change.

## Findings that drive the design

1. **Official v3.3.1 does not ship armeabi-v7a.**
   `manager/app/build.gradle.kts` lists only `arm64-v8a`, `x86_64`, `riscv64`.
   This branch adds `armeabi-v7a`, builds `ksud` for
   `armv7-linux-androideabi`, and packages it as
   `jniLibs/armeabi-v7a/libksud.so`.

2. **The injected driver fd is not inherited by `libsu` children.**
   On the live watch, the manager process had `fd 66 ->
   anon_inode:[ksu_driver]`, but its `sh` children had only fds 0, 1 and 2.
   The official `/proc/self/fd` scan in `ksucalls.rs` therefore cannot see the
   fd in a `libsu` child, and the child falls into the 32-bit `reboot` syscall
   that the app seccomp filter blocks.

3. **The kernel module is a separate blocker.**
   The available local `kernelsu.ko` is built for `5.15.202`; the watch kernel
   is `5.15.137`.  A proper `5.15.137` LKM requires the watch kernel source,
   `.config`, and `Module.symvers` (or an equivalent exact build tree).

4. **Signing is not solved by the official key.**
   The official manager signing key is not available.  A locally signed manager
   will not pass an unmodified KernelSU driver's `is_manager_apk` check.  The
   local lab uses a patched LKM (`is_manager_apk -> true`) as a bridge.

## Design: manager-side driver-fd broker

Keep official `ksud` semantics; do not replace the root shell with an agent.

1. The manager process already receives the real `[ksu_driver]` fd from the
   lab-side watcher.
2. `DriverFdBroker` (manager, `manager/app/.../ui/util/DriverFdBroker.kt`):
   - binds abstract Unix socket `@ksu_driver_broker`
   - accepts only peers with the manager's own UID
   - sends a duplicate of the driver fd with `SCM_RIGHTS`
3. `ksucalls.rs` (`watch-fd-broker` feature):
   - keeps the existing `/proc/self/fd` scan
   - if no fd, asks the broker before calling `reboot`
   - keeps the reboot+SIGSYS fallback last for normal devices
4. `KsuCli.SHELL` stays on the official path (`libksud.so debug su`), and
   `libsu` sees a real root shell.  No nano proxy and no `/system/bin/sh` bind.

This is a 32-bit userspace adaptation, not a kernel change.  It does not make
KernelSU persistent across reboots; the separate per-boot bootstrap remains the
prerequisite.

## Status (2026-10-27)

- **M0** baseline import - done.
- **M1** armv7 build - done.
- **M2** fd broker - implemented; Rust and Kotlin compile.
- **M3** on-device core path - passed with a temporary package name:
  - old manager was replaced by a test build with application ID
    `me.weishu.kernelsu`
  - UID changed from 10096 to 10137; the driver crowned the new UID
  - `runas 10137 ksud debug info` -> `flags: 0x7`
  - the app had fd 66/71/80 -> `[ksu_driver]`
  - two `sh` children ran as root
  - no new `SIGSYS 88`; the broker path works
  - the activity was created but the watch was locked, so final screenshots
    are still pending
- The repository now defaults to `io.github.umislat.kernelsu`; this new package
  has been built but **not installed** yet.
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

The upstream checkout uses symlinks for `uapi`; this fork stores copies so a
plain Windows checkout builds without junctions.

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

Current artifact:

- `manager/app/build/outputs/apk/debug/KernelSU_Watch_e7443f4_30003-debug.apk`
  (30,944,358 bytes)
- application ID `io.github.umislat.kernelsu`
- label `KernelSU Watch`
- only `lib/armeabi-v7a/`: `libadbroot.so`, `libkernelsu.so`, `libksud.so`
- signer: local Android debug key
  `3BF1577C690A4A57534AFE7338B9534FDBA659DEEDCE1E6F3ED408AE750545DC`

Checks run:

```text
cargo ndk -t armeabi-v7a -P 31 check  --features watch-fd-broker   PASS
cargo ndk -t armeabi-v7a -P 31 clippy --features watch-fd-broker   PASS
cargo ndk -t arm64-v8a    -P 31 clippy                              PASS
cargo fmt --check                                                   PASS
:app:assembleDebug -PKSU_WATCH_ONLY=true                            PASS
```

## Install caveat

The new package is separate from `me.weishu.kernelsu`, so it can be installed
side by side with the current working manager.  However:

- the lab watcher currently targets the old package; it must target
  `io.github.umislat.kernelsu` (or both packages) before the new app can receive
  the driver fd
- the APK is signed with the local Android debug key, not a release key
- the driver is the local patched LKM; release signing and a matching LKM are
  still open items
- installing side by side does not affect the current manager's UID 10137, but
  the new app will get its own UID and must be crowned by the driver

## Rollback material

`D:\smallprojects\watch5-rollback\`:

- `kernelsu-old.apk` (original manager, SHA-256 `d3a813af...`)
- `kernelsu-watch-debug.apk` (temporary-package test build)
- `ksu_manager_data_20261027.tar` (old app data, 8 KiB)

## Non-goals

- No changes to the local bootstrapping/exploit lab
- No attempt to bypass AVB/bootloader or make KernelSU persistent
- No release of the patched LKM/debug APK as a supported product

## Open items

- Point the lab watcher at `io.github.umislat.kernelsu` and install the new
  package for a clean end-to-end test
- Watch kernel source, `.config` and `Module.symvers` for `5.15.137`
- Decision on the manager signer and the corresponding LKM trust policy
- Final Home / module / superuser screenshots after unlocking the watch
- Watcher deduplication of a transient "app gone" state
- Whether to keep `targetSdk 37` or lower to 34/36 for the watch
