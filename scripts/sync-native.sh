#!/usr/bin/env bash
#
# Copies the native Responsly SDK sources into this package, so that it builds without any
# extra CocoaPods source or Maven repository:
#
#   responsly-ios-sdk/Sources/ResponslySDK            ->  ios/ResponslySDK
#   responsly-android-sdk/sdk/src/main/kotlin         ->  android/src/main/java
#   responsly-android-sdk/sdk/src/main/AndroidManifest.xml -> android/src/main/AndroidManifest.xml
#   responsly-android-sdk/sdk/consumer-rules.pro      ->  android/consumer-rules.pro
#
# and records where the copies came from in native-sdk.json (read by android/build.gradle for
# the dependency versions the Android SDK pins).
#
# Usage: scripts/sync-native.sh
#   RESPONSLY_IOS_SDK=/path/to/responsly-ios-sdk          (default: ../responsly-ios-sdk)
#   RESPONSLY_ANDROID_SDK=/path/to/responsly-android-sdk  (default: ../responsly-android-sdk)
#
# Never edit the copied files here: change them in the native repositories and sync again.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_REPO="$(cd "${RESPONSLY_IOS_SDK:-$ROOT/../responsly-ios-sdk}" && pwd)"
ANDROID_REPO="$(cd "${RESPONSLY_ANDROID_SDK:-$ROOT/../responsly-android-sdk}" && pwd)"

IOS_SOURCES="$IOS_REPO/Sources/ResponslySDK"
ANDROID_MAIN="$ANDROID_REPO/sdk/src/main"
ANDROID_SOURCES="$ANDROID_MAIN/kotlin/com/responsly/sdk"
ANDROID_MANIFEST="$ANDROID_MAIN/AndroidManifest.xml"
ANDROID_RULES="$ANDROID_REPO/sdk/consumer-rules.pro"
ANDROID_VERSIONS="$ANDROID_REPO/gradle/libs.versions.toml"

IOS_TARGET="$ROOT/ios/ResponslySDK"
ANDROID_TARGET="$ROOT/android/src/main/java/com/responsly/sdk"

fail() {
  echo "sync-native: $*" >&2
  exit 1
}

[ -f "$IOS_SOURCES/Responsly.swift" ] || fail "no iOS SDK sources in $IOS_SOURCES"
[ -f "$IOS_SOURCES/Resources/PrivacyInfo.xcprivacy" ] || fail "no PrivacyInfo.xcprivacy in $IOS_SOURCES/Resources"
[ -f "$ANDROID_SOURCES/Responsly.kt" ] || fail "no Android SDK sources in $ANDROID_SOURCES"
[ -f "$ANDROID_MANIFEST" ] || fail "no Android manifest at $ANDROID_MANIFEST"
[ -f "$ANDROID_RULES" ] || fail "no consumer rules at $ANDROID_RULES"
[ -f "$ANDROID_VERSIONS" ] || fail "no version catalog at $ANDROID_VERSIONS"

# This package only knows how to carry Kotlin sources, the manifest and the consumer rules.
# Anything else in the Android SDK's main source set needs a decision here first.
for entry in "$ANDROID_MAIN"/*; do
  case "$(basename "$entry")" in
    kotlin | AndroidManifest.xml) ;;
    *) fail "unexpected entry in the Android SDK's main source set: $entry" ;;
  esac
done

# --- iOS -------------------------------------------------------------------------------------

rm -rf "$IOS_TARGET"
mkdir -p "$IOS_TARGET"
cp -R "$IOS_SOURCES/." "$IOS_TARGET/"
find "$IOS_TARGET" -name '.DS_Store' -delete

# --- Android ---------------------------------------------------------------------------------

rm -rf "$ANDROID_TARGET"
mkdir -p "$ANDROID_TARGET"
cp -R "$ANDROID_SOURCES/." "$ANDROID_TARGET/"
find "$ANDROID_TARGET" -name '.DS_Store' -delete

# The SDK's manifest becomes this module's manifest: the INTERNET permission, the startup
# provider and the file-chooser activity are merged into the app by the Android Gradle plugin.
head -n 1 "$ANDROID_MANIFEST" | grep -q '^<?xml' || fail "the Android manifest does not start with an XML declaration"
{
  sed -n '1p' "$ANDROID_MANIFEST"
  echo '<!-- Copied from responsly-android-sdk by scripts/sync-native.sh. Do not edit here. -->'
  sed '1d' "$ANDROID_MANIFEST"
} > "$ROOT/android/src/main/AndroidManifest.xml"

{
  echo '# Copied from responsly-android-sdk by scripts/sync-native.sh. Do not edit here.'
  cat "$ANDROID_RULES"
} > "$ROOT/android/consumer-rules.pro"

# --- Record ----------------------------------------------------------------------------------

catalog_version() {
  # Reads `name = "1.2.3"` from the [versions] table of the version catalog.
  local value
  value="$(sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\\(.*\\)\"[[:space:]]*$/\\1/p" "$ANDROID_VERSIONS" | head -n 1)"
  [ -n "$value" ] || fail "no version for $1 in $ANDROID_VERSIONS"
  echo "$value"
}

source_version() {
  # Reads the SDK version constant ("1.0.0") from a source file.
  local value
  value="$(sed -n "s/.*$2.*\"\\([0-9][0-9A-Za-z.+-]*\\)\".*/\\1/p" "$1" | head -n 1)"
  [ -n "$value" ] || fail "no $2 in $1"
  echo "$value"
}

dirty() {
  # true when the copied paths have changes that are not committed
  if [ -n "$(git -C "$1" status --porcelain -- "${@:2}")" ]; then echo true; else echo false; fi
}

IOS_COMMIT="$(git -C "$IOS_REPO" rev-parse HEAD)"
ANDROID_COMMIT="$(git -C "$ANDROID_REPO" rev-parse HEAD)"
IOS_VERSION="$(source_version "$IOS_SOURCES/Responsly.swift" 'sdkVersion')"
ANDROID_VERSION="$(source_version "$ANDROID_SOURCES/Responsly.kt" 'SDK_VERSION')"
IOS_DIRTY="$(dirty "$IOS_REPO" Sources/ResponslySDK)"
ANDROID_DIRTY="$(dirty "$ANDROID_REPO" sdk/src/main sdk/consumer-rules.pro gradle/libs.versions.toml)"
WEBKIT_VERSION="$(catalog_version androidx-webkit)"
ANNOTATION_VERSION="$(catalog_version androidx-annotation)"

cat > "$ROOT/native-sdk.json" <<JSON
{
  "ios": {
    "repository": "https://github.com/responsly/responsly-ios-sdk",
    "commit": "$IOS_COMMIT",
    "dirty": $IOS_DIRTY,
    "version": "$IOS_VERSION"
  },
  "android": {
    "repository": "https://github.com/responsly/responsly-android-sdk",
    "commit": "$ANDROID_COMMIT",
    "dirty": $ANDROID_DIRTY,
    "version": "$ANDROID_VERSION",
    "dependencies": {
      "androidx.webkit:webkit": "$WEBKIT_VERSION",
      "androidx.annotation:annotation": "$ANNOTATION_VERSION"
    }
  }
}
JSON

PACKAGE_VERSION=""
if [ -f "$ROOT/package.json" ]; then
  PACKAGE_VERSION="$(sed -n 's/^  "version": "\(.*\)",$/\1/p' "$ROOT/package.json" | head -n 1)"
fi

echo "iOS      $IOS_VERSION  $IOS_COMMIT  (dirty: $IOS_DIRTY)  -> ios/ResponslySDK"
echo "Android  $ANDROID_VERSION  $ANDROID_COMMIT  (dirty: $ANDROID_DIRTY)  -> android/src/main/java/com/responsly/sdk"
echo "         androidx.webkit $WEBKIT_VERSION, androidx.annotation $ANNOTATION_VERSION"

if [ "$IOS_DIRTY" = true ] || [ "$ANDROID_DIRTY" = true ]; then
  echo "sync-native: warning: copied from a working tree with uncommitted changes" >&2
fi
if [ -n "$PACKAGE_VERSION" ] && { [ "$IOS_VERSION" != "$PACKAGE_VERSION" ] || [ "$ANDROID_VERSION" != "$PACKAGE_VERSION" ]; }; then
  echo "sync-native: warning: package.json is $PACKAGE_VERSION but the native SDKs are $IOS_VERSION (iOS) and $ANDROID_VERSION (Android)" >&2
fi
