# KernelSU Watch 5

KernelSU **v3.3.1** fork adapted for the Xiaomi Watch 5 `grass` / M2501W1:
**armeabi-v7a** userspace on an **arm64 5.15.137** kernel.

- Application ID: **`io.github.umislat.kernelsu`**
- App label: **`KernelSU Watch`**
- Upstream baseline: official KernelSU v3.3.1
- Adaptation details: [`watch/README.md`](watch/README.md)

## Highlights

- 32-bit `armeabi-v7a` manager and `ksud`
- manager-side `[ksu_driver]` fd broker for the 32-bit seccomp limitation
- watch-only manager build:

```text
./gradlew :app:assembleDebug -PKSU_WATCH_ONLY=true
```

## Build

See [`watch/README.md`](watch/README.md) for the Windows toolchain, NDK,
Rust target and build commands.

## License

KernelSU kernel code is GPL-2.0-only; the remaining parts are
GPL-3.0-or-later.  See [`LICENSE`](LICENSE).
