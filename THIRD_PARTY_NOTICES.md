Vibe Player, Copyright (C) 2026 Ravyar Tahir (ravarage), is licensed under GPL-3.0-or-later (see LICENSE).
The components below are bundled in the release APK under their own licences.

# Third-party notices

Vibe Player is licensed under GPL-3.0-or-later (see `LICENSE`). It bundles the components below.
Versions are the ones recorded in `PROGRESS.md` (Versions table) and in
`native/mpv-android/buildscripts/include/depinfo.sh` after `native/fetch.sh` has run.
Licences were stated from each project's published licensing; the full texts are in each project's
source repository (and are not reproduced here except for the two shader licences, which also live in
`app/src/main/assets/shaders/`).

## Native libraries (arm64-v8a, `player/src/main/jniLibs/arm64-v8a`)

The release contains seven shared libraries: `libmpv.so`, `libavcodec.so`, `libavfilter.so`,
`libavformat.so`, `libavutil.so`, `libswresample.so`, `libswscale.so` (plus the app's own JNI bridge).
Most dependencies are linked statically into these.

| Component | Version | Licence as built | Source |
|---|---|---|---|
| mpv | v0.41.0 | GPL-2.0-or-later (mpv is LGPL-2.1-or-later by default, but this build links FFmpeg configured with GPL options, so the combined work is GPL) | https://github.com/mpv-player/mpv |
| FFmpeg | n9.0.2 | GPL-3.0-or-later as configured (`--enable-gpl --enable-version3`) | https://github.com/FFmpeg/FFmpeg |
| dav1d | 1.5.4 | BSD-2-Clause | https://code.videolan.org/videolan/dav1d |
| libass | 0.17.5 | ISC | https://github.com/libass/libass |
| libplacebo | v7.360.1 | LGPL-2.1-or-later | https://github.com/haasn/libplacebo |
| FreeType | 2.14.3 | FreeType License (FTL) or GPL-2.0, at the recipient's option. This work is distributed under GPL-3.0-or-later, so FreeType is used under the FTL (which is GPLv3-compatible; it is not compatible with GPLv2) | https://gitlab.freedesktop.org/freetype/freetype |
| HarfBuzz | 14.6.0 | MIT (Old MIT) | https://github.com/harfbuzz/harfbuzz |
| FriBidi | 1.0.17 | LGPL-2.1-or-later | https://github.com/fribidi/fribidi |
| libunibreak | 8.0 | zlib-style licence | https://github.com/adah1972/libunibreak |
| libxml2 | 2.15.4 | MIT | https://gitlab.gnome.org/GNOME/libxml2 |
| Fontconfig | 2.18.3 | MIT-style (HPND-like) | https://gitlab.freedesktop.org/fontconfig/fontconfig |
| Mbed TLS | 3.6.7 | Apache-2.0 OR GPL-2.0-or-later | https://github.com/Mbed-TLS/mbedtls |
| Lua | 5.2.4 | MIT | https://www.lua.org/ |
| LLVM libc++ (`libc++_shared.so`, from the NDK) | NDK r30 | Apache-2.0 WITH LLVM-exception | https://libcxx.llvm.org/ |
| shaderc | 2022.3 (from Android NDK r30) | Apache-2.0 | https://github.com/google/shaderc |
| glslang, SPIRV-Tools, SPIRV-Headers (inside shaderc) | as shipped in NDK r30 | glslang: BSD-3-Clause and others; SPIRV-Tools and SPIRV-Headers: Apache-2.0 / MIT-style | https://github.com/KhronosGroup/glslang, https://github.com/KhronosGroup/SPIRV-Tools |
| mpv-android build scripts | commit c2e02fe111541e4063ab8f868bf629649ed937a3 (+ patches in `native/patches/`) | MIT | https://github.com/mpv-android/mpv-android |

Notes:
- shaderc, glslang and SPIRV-Tools are only linked in the Vulkan-enabled build, which is the default build
  of `native/build.sh` (`VULKAN=1`).
- Checked on the shipped binaries (2026-10-14, `readelf -d` and `strings`): `libmpv.so` links FFmpeg's
  libraries dynamically and contains libass, FreeType, HarfBuzz, FriBidi, Fontconfig, libxml2,
  libunibreak, Lua, Mbed TLS, libplacebo and shaderc/glslang/SPIRV-Tools statically; it also needs the
  NDK's `libc++_shared.so`. curl is built by the mpv-android scripts but is not linked into the shipped
  libraries, so it is not listed.
- The licence of each component was not re-verified against its tag's `COPYING` files for this document.

## Bundled shaders (`app/src/main/assets/shaders/`)

| Component | Licence | Notes |
|---|---|---|
| Anime4K v4.0.1 (bloc97) | MIT, Copyright (c) 2019 bloc97 | https://github.com/bloc97/Anime4K ; licence in `LICENSE_Anime4K` |
| Snapdragon Game Super Resolution v1, mpv GLSL port by agyild | BSD-3-Clause, Copyright (c) 2023, Qualcomm Innovation Center, Inc. | https://gist.github.com/agyild/7715b6b1f38427839d58f80884902cab (commit 0ac71744883918af581f0824435e46b3b364a0d0); licence in `LICENSE_GSR` |

## Android libraries (Maven)

All Apache-2.0, from Google (https://maven.google.com, https://developer.android.com/jetpack):
AndroidX Activity, Lifecycle, Navigation, Core, Jetpack Compose (BOM 2026.09.00), Room 2.8.5,
DataStore 1.2.1, Media3 (media session) 1.11.1, Profile Installer 1.4.1. Kotlin and kotlinx libraries
(Apache-2.0, https://github.com/JetBrains/kotlin). Exact artifacts: `gradle/libs.versions.toml`.

## Corresponding source

The binaries in the release APK are built entirely from this repository plus the pinned upstream sources.
To obtain the complete corresponding source of the native libraries and rebuild them:

1. Check out the tag of the release (for example `v0.5.0`) of this repository. It contains the app and
   JNI sources (`app/`, `player/`), the build scripts (`native/fetch.sh`, `native/build.sh`) and all
   patches (`native/patches/*.patch`, `native/patches/optional/`).
2. `native/fetch.sh` clones https://github.com/mpv-android/mpv-android.git, checks out the pinned commit
   `c2e02fe111541e4063ab8f868bf629649ed937a3` and applies `native/patches/*.patch`. These patches pin
   mpv v0.41.0, FFmpeg n9.0.2, dav1d 1.5.4, libass 0.17.5, libplacebo v7.360.1, HarfBuzz 14.6.0 and the
   other versions listed above, trim and configure FFmpeg, and set the optimisation level.
3. `native/build.sh` downloads the pinned dependency sources (`buildscripts/download.sh`) and builds the
   libraries (default: arm64-v8a, Vulkan-enabled, `-O2`). Variants: `VULKAN=0` (OpenGL ES only), `TUNED=1`.
4. The build produces the seven `.so` files that go into `player/src/main/jniLibs/arm64-v8a`.

The FFmpeg configure options are in `native/mpv-android/buildscripts/scripts/ffmpeg.sh` after step 2 and in
`native/patches/0002-ffmpeg-trim-and-disable-avdevice.patch`. Anyone who received the APK may request or
obtain this source from the repository's GitHub page, which satisfies the source obligations of the GPL
for the binaries distributed in the release. See `docs/BUILDING.md` for details.

Vibe Player is not affiliated with MX Player, mpv, Qualcomm or Anime4K; names are used only to
credit or describe.
