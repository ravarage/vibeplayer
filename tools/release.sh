#!/bin/bash
# Builds the release APK on this PC (signed with ~/.android/debug.keystore, the key the phone's install
# uses), installs it on the phone in place, and publishes the GitHub release with the APK and SHA256SUMS.
# Run on main after the PR is merged. Notes come from docs/releases/<version>.md.
#
#   tools/release.sh               # build, install on the phone, publish
#   tools/release.sh --no-install  # skip the phone install
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$(pwd)/tools/bin:$PATH"
INSTALL=1
for a in "$@"; do
  case "$a" in
    --no-install) INSTALL=0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
NOTES="docs/releases/$VERSION.md"
APK="VibePlayer-$VERSION-arm64.apk"
OUT="dist/$VERSION"

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "switch to main first (git checkout main && git pull)"; exit 1; }
if [ -n "$(git status --porcelain)" ]; then
  echo "working tree not clean (the APK must match the commit). Commit, stash or remove these first:"
  git status --short
  exit 1
fi
[ -f "$NOTES" ] || { echo "missing $NOTES"; exit 1; }
[ -f "$HOME/.android/debug.keystore" ] || { echo "missing ~/.android/debug.keystore"; exit 1; }
git pull --ff-only
if git ls-remote --exit-code --tags origin "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION is already released"; exit 1
fi

echo "== Build $VERSION (release)"
./gradlew :app:assembleRelease 2>&1 | tail -3
mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/$APK"
(cd "$OUT" && sha256sum "$APK" > SHA256SUMS && cat SHA256SUMS)

if [ "$INSTALL" = 1 ]; then
  echo "== Install on the phone (keeps history and settings)"
  adb install -r "$OUT/$APK" | tail -1
fi

echo "== Publish v$VERSION"
gh release create "v$VERSION" "$OUT/$APK" "$OUT/SHA256SUMS" \
  --target "$(git rev-parse HEAD)" --title "Vibe Player $VERSION" --notes-file "$NOTES"
