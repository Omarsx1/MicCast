#!/bin/bash
# Builds a universal (arm64 + x86_64) MicCast.app and packages it into a DMG.
# Usage: bash package_release.sh [version] [build-number]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

APP_NAME="MicCast"
VERSION="${1:-1.0.0}"
BUILD_NUMBER="${2:-1}"
DIST="dist"
SCRATCH_ARM=".build-release-arm64"
SCRATCH_X86=".build-release-x86_64"
BUNDLE="$DIST/stage/$APP_NAME.app"
DMG="$DIST/$APP_NAME-$VERSION.dmg"

echo "🔨 Building $APP_NAME $VERSION ($BUILD_NUMBER)"

swift build -c release --arch arm64 --scratch-path "$SCRATCH_ARM"
swift build -c release --arch x86_64 --scratch-path "$SCRATCH_X86"

ARM_BIN="$SCRATCH_ARM/out/Products/Release/MicCastMac"
X86_BIN="$SCRATCH_X86/out/Products/Release/MicCastMac"
[ -f "$ARM_BIN" ] || ARM_BIN="$SCRATCH_ARM/release/MicCastMac"
[ -f "$X86_BIN" ] || X86_BIN="$SCRATCH_X86/release/MicCastMac"

for binary in "$ARM_BIN" "$X86_BIN"; do
    if [ ! -f "$binary" ]; then
        echo "❌ Missing build product: $binary"
        exit 1
    fi
done

rm -rf "$DIST"
mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources"

lipo -create "$ARM_BIN" "$X86_BIN" -output "$BUNDLE/Contents/MacOS/$APP_NAME"
chmod +x "$BUNDLE/Contents/MacOS/$APP_NAME"

cp MicCast/Info.plist "$BUNDLE/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$BUNDLE/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$BUNDLE/Contents/Info.plist" 2>/dev/null \
    || /usr/libexec/PlistBuddy -c "Add :CFBundleVersion string $BUILD_NUMBER" "$BUNDLE/Contents/Info.plist"

codesign --force --deep --sign - "$BUNDLE"
codesign --verify --strict "$BUNDLE"

lipo -info "$BUNDLE/Contents/MacOS/$APP_NAME"

ln -s /Applications "$DIST/stage/Applications"
hdiutil create -volname "$APP_NAME" -srcfolder "$DIST/stage" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$DIST/stage"

shasum -a 256 "$DMG" | awk '{print $1}' > "$DMG.sha256"

echo "✅ $DMG"
