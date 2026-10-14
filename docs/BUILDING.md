# Building from source

## Prerequisites

- A Linux host (developed on Arch-based Linux; other distributions untested). The native scripts use bash,
  git, meson, ninja, cmake, nasm, python3, curl and standard build tools. Versions used are in `PROGRESS.md`.
- Android Studio, for its JetBrains Runtime (JBR) as the build JDK. `native/build.sh` currently hardcodes
  `JAVA_HOME` to the developer's Android Studio path
  (`/home/rava/.local/share/JetBrains/Toolbox/apps/android-studio/jbr`); edit that line for your machine.
  Gradle also needs a JDK (the project used the JBR 25).
- The Android SDK (platform 37) and NDK r30 (30.0.16248370). The native build scripts download the NDK and the
  dependency sources themselves; point `local.properties` (`sdk.dir`) at your Android SDK for Gradle.
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
| `TUNED=1 native/build.sh` | `-O2 -mcpu=cortex-a78 -flto=thin` plus Vulkan (script default VULKAN=1) | `native/out/tuned/arm64-v8a` |
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
