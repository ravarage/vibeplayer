#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MPV_ANDROID_DIR="$REPO_ROOT/native/mpv-android"
PINNED_COMMIT="c2e02fe111541e4063ab8f868bf629649ed937a3"

if [ ! -d "$MPV_ANDROID_DIR/.git" ]; then
    echo "Cloning mpv-android..."
    git clone https://github.com/mpv-android/mpv-android.git "$MPV_ANDROID_DIR"
fi

echo "Checking out pinned commit $PINNED_COMMIT in mpv-android..."
git -C "$MPV_ANDROID_DIR" checkout -f "$PINNED_COMMIT"
git -C "$MPV_ANDROID_DIR" reset --hard "$PINNED_COMMIT"

if compgen -G "$SCRIPT_DIR/patches/*.patch" > /dev/null; then
    for patch_file in "$SCRIPT_DIR"/patches/*.patch; do
        echo "Applying patch $(basename "$patch_file")..."
        git -C "$MPV_ANDROID_DIR" apply "$patch_file"
    done
fi

echo "Fetch and patch complete."
