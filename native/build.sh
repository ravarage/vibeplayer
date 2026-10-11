#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MPV_ANDROID_DIR="$REPO_ROOT/native/mpv-android"
BUILDSCRIPTS_DIR="$MPV_ANDROID_DIR/buildscripts"

# Point to Android Studio JBR
export JAVA_HOME="/home/rava/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"

# Ensure sources are fetched and patched
if [ ! -d "$MPV_ANDROID_DIR" ]; then
    "$SCRIPT_DIR/fetch.sh"
fi

download_deps() {
    echo "Running buildscripts download step..."
    cd "$BUILDSCRIPTS_DIR"
    ./download.sh
}

build_native() {
    echo "Building native libraries for arm64..."
    cd "$BUILDSCRIPTS_DIR"
    ./buildall.sh --arch arm64 mpv

    local out_dir="$REPO_ROOT/player/src/main/jniLibs/arm64-v8a"
    mkdir -p "$out_dir"
    echo "Copying .so libraries to $out_dir..."
    cp -v "$BUILDSCRIPTS_DIR/prefix/arm64/lib"/*.so "$out_dir/"
    echo "Native build complete."
}

action="${1:-build}"

case "$action" in
    download)
        download_deps
        ;;
    build)
        # Ensure SDK and deps exist
        if [ ! -d "$BUILDSCRIPTS_DIR/sdk/android-ndk-r30" ] || [ ! -d "$BUILDSCRIPTS_DIR/deps/mpv" ]; then
            download_deps
        fi
        build_native
        ;;
    *)
        echo "Usage: $0 [download|build]"
        exit 1
        ;;
esac
