#!/usr/bin/env bash
set -euo pipefail
./gradlew --no-daemon assembleDebug
printf '\nAPK created at: app/build/outputs/apk/debug/app-debug.apk\n'
