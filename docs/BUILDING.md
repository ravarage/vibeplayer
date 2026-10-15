# Building from source

## Prerequisites

- A Linux host (developed on Arch-based Linux; other distributions untested). The native scripts use bash,
  git, meson, ninja, cmake, nasm, python3, curl, wget, unzip, autoconf, libtool, pkg-config, gperf and
  standard build tools. Versions used are in `PROGRESS.md`.
- A JDK 17 or newer with `javac` (the project used Android Studio's JetBrains Runtime 25). `native/build.sh`
  uses `$JAVA_HOME` if set, otherwise the Android Studio JBR under `~/.local/share/JetBrains/Toolbox`,
  otherwise the `javac` on `PATH`. Gradle needs a JDK too.
- The Android SDK (platform 37) and NDK r30 (30.0.16248370). The native build scripts download the NDK and the
  dependency sources themselves; the NDK lands next to the SDK, not inside it,
  so Gradle needs both paths in `local.properties` in the project root:

  ```properties
  sdk.dir=<repo>/native/mpv-android/buildscripts/sdk/android-sdk-linux
  ndk.dir=<repo>/native/mpv-android/buildscripts/sdk/android-ndk-r30
  ```

  The scripts install SDK platform 37.0 (`platforms;android-37.0`, the package `compileSdk = 37` uses; sdkmanager has no
  `android-37`), so Gradle does not download the platform itself (it may still fetch platform-tools or build-tools on the first build). An SDK installed before this change (2026-10-15) has only
  platform 36; there Gradle downloads platform 37 on its first build (network needed).
- Disk space and time: the first native build downloads and compiles all dependencies.

## Native libraries

```sh
native/fetch.sh     # clones mpv-android, checks out the pinned commit, applies native/patches/*.patch
native/build.sh     # downloads the dependencies, builds arm64-v8a, writes player/src/main/jniLibs/arm64-v8a
```

The built libraries are not committed (`player/src/main/jniLibs/` and `native/out/` are gitignored), so this step is required before the app build.
Variants (see `ARCHITECTURE.md`):

| Command | Result | Output |
|---|---|---|
| `native/build.sh` | default: Vulkan-enabled (libplacebo + shaderc), `-O2` | `player/src/main/jniLibs/arm64-v8a` |
| `VULKAN=0 native/build.sh` | OpenGL ES only | `native/out/gles/arm64-v8a` |
| `native/build.sh` (default since 2026-10-15) | tuned `-O2 -mcpu=cortex-a78 -flto=thin` plus Vulkan | `player/src/main/jniLibs/arm64-v8a` |
| `TUNED=0 native/build.sh` | generic `-O2` plus Vulkan | `native/out/generic/arm64-v8a` |
| `TUNED=1 VULKAN=0 native/build.sh` | tuned, OpenGL ES only | `native/out/tuned-gles/arm64-v8a` |

The tuned variants are experimental (P9 is not finished). `native/build.sh` is authoritative for output paths (ARCHITECTURE.md lists older names). Each build cleans the shared prefix first.

## The app

```sh
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/
./gradlew :app:assembleDebug
```

compileSdk, targetSdk and minSdk are all 37, and the ABI filter is arm64-v8a, so the APK installs only on
Android 17 arm64 devices.

## Signing

`app/build.gradle.kts` defines a signing config called `local` that reads the developer's keystore
(`~/.android/debug.keystore`), used for both debug and release builds so that updates install in place.
That key is not in the repository and must not be reused: create your own keystore and change the
`signingConfigs` block, or the build will fail or sign with a different key (an APK signed with another key
cannot update over one signed with the original; uninstall first, which deletes app data).

## Test tooling

Requires adb and a connected device with the test clips in `/sdcard/Movies/vibeplayer-test/` (see `SPEC.md`).

- `tools/check_video.py`: takes screenshots over adb and reports whether a moving picture is visible
  (non-black and changed percentages).
- `tools/devcheck/`: on-device regression suite (`python3 tools/devcheck/run.py`, see its `README.md`).
- `tools/bench/run_matrix.py`: benchmark matrices for renderer and shader comparisons (see `docs/BENCHMARK.md`).
- Unit tests: `./gradlew :app:testDebugUnitTest` (JVM tests, no device).

The agent rules for contributors (human or AI) are in `AGENT_RULES.md`.
