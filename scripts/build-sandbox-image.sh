#!/bin/bash
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:-goldenera-node:sandbox-local}"

if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: sandbox image requires a clean committed worktree."
    exit 1
fi

BUILD_GIT_COMMIT="$(git rev-parse HEAD | tr '[:upper:]' '[:lower:]')"
CRYPTOJ_VERSION="$(mise exec -- mvn help:evaluate -Dexpression=goldenera-cryptoj.version -q -DforceStdout)"
CRYPTOJ_JAR="${HOME}/.m2/repository/global/goldenera/cryptoj/goldenera-cryptoj/${CRYPTOJ_VERSION}/goldenera-cryptoj-${CRYPTOJ_VERSION}.jar"
if [ ! -f "$CRYPTOJ_JAR" ]; then
    echo "ERROR: CryptoJ artifact is not installed locally: $CRYPTOJ_JAR"
    exit 1
fi
CRYPTOJ_SHA256="$(shasum -a 256 "$CRYPTOJ_JAR" | awk '{print $1}')"
RANDOMX_SOURCE_COMMIT="$(mise exec -- mvn help:evaluate -Dexpression=goldenera-randomx.source.commit -q -DforceStdout)"

mise exec -- mvn clean package -Prelease-artifact -DskipTests \
  -Dgoldenera.git.commit="$BUILD_GIT_COMMIT" \
  -Dgoldenera.cryptoj.sha256="$CRYPTOJ_SHA256"

docker build \
  --target sandbox-local-runtime \
  --build-arg BUILD_GIT_COMMIT="$BUILD_GIT_COMMIT" \
  --build-arg CRYPTOJ_SHA256="$CRYPTOJ_SHA256" \
  --build-arg RANDOMX_SOURCE_COMMIT="$RANDOMX_SOURCE_COMMIT" \
  -t "$IMAGE_TAG" .

IMAGE_ID="$(docker image inspect "$IMAGE_TAG" --format '{{.Id}}')"
echo "sandboxImage=$IMAGE_TAG"
echo "sandboxImageId=$IMAGE_ID"
echo "gitCommit=$BUILD_GIT_COMMIT"
echo "cryptoJSha256=$CRYPTOJ_SHA256"
echo "randomXSourceCommit=$RANDOMX_SOURCE_COMMIT"
