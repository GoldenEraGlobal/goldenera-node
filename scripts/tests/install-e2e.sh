#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/goldenera-installer-e2e.XXXXXX")"
API_PORT="${GOLDENERA_E2E_API_PORT:-18080}"
P2P_PORT="${GOLDENERA_E2E_P2P_PORT:-19000}"
EXPLORER_ENABLE="${GOLDENERA_E2E_EXPLORER_ENABLE:-false}"

# shellcheck disable=SC2329 # Invoked through the EXIT trap.
cleanup() {
  if [ -f "$TEST_ROOT/compose.yaml" ]; then
    (cd "$TEST_ROOT" && docker compose down >/dev/null 2>&1) || true
  fi
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

docker image inspect goldenera-node:sandbox-local >/dev/null 2>&1 || {
  printf 'Missing goldenera-node:sandbox-local. Run ./scripts/build-sandbox-image.sh first.\n' >&2
  exit 1
}

GOLDENERA_INSTALL_DIR="$TEST_ROOT" \
GOLDENERA_P2P_HOST="127.0.0.1" \
GOLDENERA_P2P_PORT="$P2P_PORT" \
GOLDENERA_API_PORT="$API_PORT" \
GOLDENERA_MINING_ENABLE=false \
GOLDENERA_EXPLORER_ENABLE="$EXPLORER_ENABLE" \
GOLDENERA_ADMIN_PASSWORD="e2e-admin-password" \
GOLDENERA_POSTGRES_PASSWORD="e2e-postgres-password" \
GOLDENERA_HMAC_SECRET="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
GOLDENERA_AES_SECRET="AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=" \
"$ROOT_DIR/scripts/install.sh" --local-image --non-interactive

for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:${API_PORT}/api/core/v1/health/live" >"$TEST_ROOT/health.json" 2>/dev/null; then
    grep -Fq '"status":"UP"' "$TEST_ROOT/health.json" || {
      printf 'Unexpected health response: %s\n' "$(cat "$TEST_ROOT/health.json")" >&2
      exit 1
    }
    printf 'GoldenEra installer end-to-end test passed with the local sandbox image.\n'
    exit 0
  fi
  if ! docker inspect goldenera_node --format '{{.State.Running}}' 2>/dev/null | grep -q true; then
    docker logs --tail 160 goldenera_node >&2 || true
    exit 1
  fi
  sleep 2
done

docker logs --tail 160 goldenera_node >&2 || true
printf 'Timed out waiting for the node liveness endpoint.\n' >&2
exit 1
