#!/bin/bash
set -e

# ==============================================================================
# CONFIG
# ==============================================================================
CREDENTIALS_FILE=".github_creds"
IMAGE_TAG="goldenera-node:local"
TEMP_TOKEN_FILE=".temp_gh_token"

# ==============================================================================
# LOAD CREDENTIALS
# ==============================================================================
if [ -f "$CREDENTIALS_FILE" ]; then
    echo ">>> Loading credentials from $CREDENTIALS_FILE..."
    source "$CREDENTIALS_FILE"
fi

if [ -z "$GITHUB_USER" ] || [ -z "$GITHUB_TOKEN" ]; then
    echo "❌ ERROR: Credentials missing."
    echo "Please create '.github_creds' with GITHUB_USER and GITHUB_TOKEN."
    exit 1
fi

# ==============================================================================
# SECURE BUILD
# ==============================================================================
echo "===================================================================="
echo ">>> Building Docker Image ($IMAGE_TAG)..."
echo ">>> User: $GITHUB_USER"
echo ">>> Mode: SECURE (Temp file strategy)"
echo "===================================================================="

echo "$GITHUB_TOKEN" > "$TEMP_TOKEN_FILE"

trap "rm -f $TEMP_TOKEN_FILE" EXIT

docker build \
  --build-arg GITHUB_ACTOR="$GITHUB_USER" \
  --secret id=github_token,src="$TEMP_TOKEN_FILE" \
  -t "$IMAGE_TAG" .

echo "===================================================================="
echo "✅ BUILD SUCCESSFUL"
echo "Run: docker compose -f docker-compose.local.yml up -d"
echo "===================================================================="
