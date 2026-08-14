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

BUILD_GIT_COMMIT="${BUILD_GIT_COMMIT:-$(git rev-parse HEAD | tr '[:upper:]' '[:lower:]')}"
CRYPTOJ_VERSION="$(./mvnw help:evaluate -Dexpression=goldenera-cryptoj.version -q -DforceStdout)"
CRYPTOJ_JAR="${HOME}/.m2/repository/global/goldenera/cryptoj/goldenera-cryptoj/${CRYPTOJ_VERSION}/goldenera-cryptoj-${CRYPTOJ_VERSION}.jar"
if [ ! -f "$CRYPTOJ_JAR" ]; then
    echo "ERROR: CryptoJ artifact is not installed locally: $CRYPTOJ_JAR"
    exit 1
fi
CRYPTOJ_SHA256="${CRYPTOJ_SHA256:-$(shasum -a 256 "$CRYPTOJ_JAR" | awk '{print $1}')}"
RANDOMX_SOURCE_COMMIT="${RANDOMX_SOURCE_COMMIT:-$(./mvnw help:evaluate -Dexpression=goldenera-randomx.source.commit -q -DforceStdout)}"
PINNED_RANDOMX_SOURCE_COMMIT="$(./mvnw help:evaluate -Dexpression=goldenera-randomx.source.commit -q -DforceStdout)"
if [ "$RANDOMX_SOURCE_COMMIT" != "$PINNED_RANDOMX_SOURCE_COMMIT" ]; then
    echo "ERROR: RANDOMX_SOURCE_COMMIT must match the authoritative Maven pin."
    exit 1
fi

docker build \
  --build-arg GITHUB_ACTOR="$GITHUB_USER" \
  --build-arg BUILD_GIT_COMMIT="$BUILD_GIT_COMMIT" \
  --build-arg CRYPTOJ_SHA256="$CRYPTOJ_SHA256" \
  --build-arg RANDOMX_SOURCE_COMMIT="$RANDOMX_SOURCE_COMMIT" \
  --secret id=github_token,src="$TEMP_TOKEN_FILE" \
  -t "$IMAGE_TAG" .

echo "===================================================================="
echo "✅ BUILD SUCCESSFUL"
echo "Run: docker compose -f docker-compose.local.yml up -d"
echo "===================================================================="
