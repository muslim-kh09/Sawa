#!/usr/bin/env bash
set -e

# Default values if not provided dynamically
KEYSTORE_PATH=${1:-"release-keystore.jks"}
KEY_ALIAS=${2:-"btl-release"}
KEY_PASSWORD=${3:-"SuperSecretPassword123!"}
STORE_PASSWORD=${4:-"SuperSecretPassword123!"}
KEY_ALGORITHM=${5:-"RSA"}
KEY_SIZE=${6:-"2048"}
VALIDITY_DAYS=${7:-"10000"}

echo "============================================================"
echo "Generating BTL Release Keystore"
echo "Path: $KEYSTORE_PATH"
echo "Alias: $KEY_ALIAS"
echo "Algorithm: $KEY_ALGORITHM"
echo "Size: $KEY_SIZE"
echo "Validity: $VALIDITY_DAYS days"
echo "============================================================"

# Generate the secure keystore
keytool -genkeypair -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -keyalg "$KEY_ALGORITHM" \
  -keysize "$KEY_SIZE" \
  -sigalg SHA256withRSA \
  -validity "$VALIDITY_DAYS" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=BTL Protocol, OU=Security, O=BTL, L=Mesh, S=Offline, C=XX"

echo ""
echo "Keystore successfully generated at $KEYSTORE_PATH"
echo ""

# Extract the SHA-256 fingerprint for integrity validation inside RaspEngine.kt
echo "============================================================"
echo "EXTRACTING SHA-256 FINGERPRINT"
echo "============================================================"
keytool -list -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -storepass "$STORE_PASSWORD" | grep "SHA256: "

echo ""
echo "IMPORTANT INTEGRITY STEP:"
echo "1. Copy the SHA256 hex string output above."
echo "2. Paste it directly into com/btl/protocol/data/security/RaspEngine.kt"
echo "   as EXPECTED_SIGNATURE_HASH."
echo "   Example: private const val EXPECTED_SIGNATURE_HASH = \"AB:CD:EF...\""
echo "============================================================"
