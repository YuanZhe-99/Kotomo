# Development

## Requirements

- JDK 21 or later. The documentation check runs on it directly, and the Gradle build will use it.
- From P0: the Android SDK with the newest platform, the Android NDK, and CMake and Ninja from the
  SDK; Bazelisk too, if spike S3 keeps Mozc's own Bazel build. P0 chooses the versions and locks
  them in `build-lock.json`.
- A real Android device for every claim about input latency, the IME lifecycle and on-device AI.
  An `x86_64` emulator is enough for layouts and most instrumentation tests.

## Commands

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
```

The Gradle build, the host engine tests and the conversion corpus runner join this list with their
first code; `PLAN.md` §17 lists the intended commands.

## Testing principles

Tests protect product contracts and error-prone logic, preferably table-driven. Every fixed bug
gets a regression test, and every conversion bug a line in the conversion corpus. Each test names
the test ID (`T01`…) or gate it covers. Latency, memory and on-device AI results count only on a
real device and are reported with the device, the Android version and the build type (debug or
release with R8). Results are `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`.
