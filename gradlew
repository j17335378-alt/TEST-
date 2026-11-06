#!/usr/bin/env sh
# Lightweight bootstrap for this project: downloads the pinned Gradle distribution if needed.
set -eu
VERSION="8.13"
GRADLE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/hajala-gradle-${VERSION}"
GRADLE_BIN="$GRADLE_ROOT/gradle-${VERSION}/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$GRADLE_ROOT"
  TMP_ZIP="$GRADLE_ROOT/gradle-${VERSION}-bin.zip"
  if [ ! -f "$TMP_ZIP" ]; then
    command -v curl >/dev/null 2>&1 || { echo 'curl is required to download Gradle 8.13.' >&2; exit 1; }
    curl -fL --retry 3 --connect-timeout 20 "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" -o "$TMP_ZIP"
  fi
  command -v unzip >/dev/null 2>&1 || { echo 'unzip is required to unpack Gradle.' >&2; exit 1; }
  rm -rf "$GRADLE_ROOT/gradle-${VERSION}"
  unzip -q "$TMP_ZIP" -d "$GRADLE_ROOT"
  rm -f "$TMP_ZIP"
fi
exec "$GRADLE_BIN" "$@"
