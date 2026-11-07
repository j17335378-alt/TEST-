#!/usr/bin/env bash
# Plain-JVM tests for the update/migration core (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
javac -d "$OUT" app/src/main/java/com/hajala/app/VersionUtil.java \
  app/src/main/java/com/hajala/app/MigrationRunner.java \
  app/src/main/java/com/hajala/app/PathRemap.java \
  scripts/test/com/hajala/app/CoreTests.java
java -cp "$OUT" com.hajala.app.CoreTests
