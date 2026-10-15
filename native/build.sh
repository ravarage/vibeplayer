#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MPV_ANDROID_DIR="$REPO_ROOT/native/mpv-android"
BUILDSCRIPTS_DIR="$MPV_ANDROID_DIR/buildscripts"

# JDK: $JAVA_HOME if set, else the developer's Android Studio JBR, else the javac on PATH.
if [[ -z "${JAVA_HOME:-}" ]]; then
	if [[ -d "$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr" ]]; then
		export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
	elif command -v javac >/dev/null 2>&1; then
		export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
	fi
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -d "$MPV_ANDROID_DIR/.git" ]]; then
	"$SCRIPT_DIR/fetch.sh"
fi

download_deps() {
	cd "$BUILDSCRIPTS_DIR"
	./download.sh
}

select_vulkan_patch() {
	local patch_file="$SCRIPT_DIR/patches/optional/0004-vulkan-shaderc.patch"
	if [[ "${VULKAN:-1}" == "1" ]]; then
		if ! git -C "$MPV_ANDROID_DIR" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
			git -C "$MPV_ANDROID_DIR" apply --check "$patch_file"
			git -C "$MPV_ANDROID_DIR" apply "$patch_file"
		fi
	else
		if git -C "$MPV_ANDROID_DIR" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
			git -C "$MPV_ANDROID_DIR" apply --reverse "$patch_file"
		fi
	fi
}

action="${1:-build}"
if [[ "$action" == download ]]; then
	download_deps
	exit 0
elif [[ "$action" != build ]]; then
	echo "Usage: $0 [download|build]" >&2
	exit 1
fi

if [[ ! -d "$BUILDSCRIPTS_DIR/sdk/android-ndk-r30" || ! -d "$BUILDSCRIPTS_DIR/deps/mpv" ]]; then
	download_deps
fi

select_vulkan_patch

tuned=0
[[ "${TUNED:-1}" == "1" ]] && tuned=1
vulkan=0
[[ "${VULKAN:-1}" == "1" ]] && vulkan=1
# Since 2026-10-13 (phone has a Vulkan 1.3 driver) the default libraries include Vulkan.
# Since 2026-10-15 the default is also tuned (P9 flags; user decision): TUNED=0 builds the generic
# -O2 libraries into native/out/generic. VULKAN=0 builds the GLES-only variants.
variant=default
if (( tuned && ! vulkan )); then variant=tuned-gles
elif (( ! tuned && vulkan )); then variant=generic
elif (( ! tuned )); then variant=gles
fi

if (( tuned )); then export NATIVE_TUNED=1; else export NATIVE_TUNED=0; fi

echo "Building native variant: $variant"
cd "$BUILDSCRIPTS_DIR"
# This buildscripts checkout has one shared prefix/build directory. Clean all target build dirs
# and the prefix on every selection, so no object or static archive can leak across flag sets.
mkdir -p prefix  # missing on a fresh clone
find prefix -mindepth 1 -delete
./buildall.sh --clean --arch arm64 mpv

if [[ "$variant" == default ]]; then
	out_dir="$REPO_ROOT/player/src/main/jniLibs/arm64-v8a"
else
	out_dir="$REPO_ROOT/native/out/$variant/arm64-v8a"
fi
mkdir -p "$out_dir"
cp -v "$BUILDSCRIPTS_DIR/prefix/arm64/lib"/*.so "$out_dir/"
echo "Native $variant build complete: $out_dir"
