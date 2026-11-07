#!/usr/bin/env bash
# Creates the permanent ARCANUM signing key. Run ONCE, on your own device, and keep the output files safe.
# Termux:  pkg install openjdk-17    then   bash scripts/make-keystore.sh
set -euo pipefail
OUT="${1:-$HOME/arcanum-signing}"
mkdir -p "$OUT"
PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
keytool -genkeypair -v -keystore "$OUT/arcanum-release.jks" -storetype PKCS12 \
  -alias arcanum -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=ARCANUM"
base64 -w0 "$OUT/arcanum-release.jks" > "$OUT/keystore.b64" 2>/dev/null || base64 "$OUT/arcanum-release.jks" | tr -d '\n' > "$OUT/keystore.b64"
cat > "$OUT/key-secrets.txt" <<TXT
ARCANUM_KEYSTORE_PASSWORD=$PASS
ARCANUM_KEY_ALIAS=arcanum
(ARCANUM_KEYSTORE_B64 = the content of keystore.b64)
TXT
echo "Done. Files in: $OUT  -- back them up somewhere private. Never commit them."
