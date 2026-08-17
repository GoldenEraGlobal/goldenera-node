#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/goldenera-installer-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }
assert_contains() { grep -Fq "$2" "$1" || fail "$1 does not contain: $2"; }
assert_not_contains() { ! grep -Fq "$2" "$1" || fail "$1 unexpectedly contains: $2"; }

install_dir="$TEST_ROOT/local"
mnemonic="alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
GOLDENERA_INSTALL_DIR="$install_dir" \
GOLDENERA_P2P_HOST="203.0.113.10" \
GOLDENERA_MINING_ENABLE=true \
GOLDENERA_BENEFICIARY_ADDRESS="0x1111111111111111111111111111111111111111" \
GOLDENERA_EXPLORER_ENABLE=true \
GOLDENERA_IDENTITY_MNEMONIC="$mnemonic" \
GOLDENERA_ADMIN_PASSWORD="admin-secret" \
GOLDENERA_POSTGRES_PASSWORD="postgres-secret" \
GOLDENERA_HMAC_SECRET="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
GOLDENERA_AES_SECRET="AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=" \
"$ROOT_DIR/scripts/install.sh" --local-image --non-interactive --skip-docker-check >/dev/null

assert_file "$install_dir/.env"
assert_file "$install_dir/compose.yaml"
assert_file "$install_dir/goldenera"
assert_file "$install_dir/node_data/.node_identity"
assert_contains "$install_dir/.env" "GOLDENERA_IMAGE=goldenera-node:sandbox-local"
assert_contains "$install_dir/.env" "GOLDENERA_PULL_POLICY=never"
assert_contains "$install_dir/.env" "MINING_ENABLE=true"
assert_contains "$install_dir/.env" "POSTGRESQL_PASSWORD=postgres-secret"
assert_contains "$install_dir/compose.yaml" "image: postgres:18.1-alpine"
[ "$(cat "$install_dir/node_data/.node_identity")" = "$mnemonic" ] || fail "identity mnemonic changed"
[ "$(stat -c '%a' "$install_dir/.env" 2>/dev/null || stat -f '%Lp' "$install_dir/.env")" = 600 ] || fail ".env permissions are not 600"

# Reconfiguration must preserve persisted encryption and database secrets.
GOLDENERA_INSTALL_DIR="$install_dir" \
GOLDENERA_P2P_HOST="203.0.113.11" \
GOLDENERA_MINING_ENABLE=false \
GOLDENERA_EXPLORER_ENABLE=false \
"$ROOT_DIR/scripts/install.sh" reconfigure --local-image --non-interactive --skip-docker-check >/dev/null
assert_contains "$install_dir/.env" "POSTGRESQL_PASSWORD=postgres-secret"
assert_contains "$install_dir/.env" "SECURITY_HMAC_SECRET=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
assert_contains "$install_dir/.env" "P2P_HOST=203.0.113.11"
assert_not_contains "$install_dir/compose.yaml" "image: postgres:18.1-alpine"

# The local-image update path must recreate containers without attempting a pull.
fake_bin="$TEST_ROOT/bin"
mkdir -p "$fake_bin"
cat >"$fake_bin/docker" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >>"$DOCKER_CALL_LOG"
exit 0
EOF
chmod +x "$fake_bin/docker"
DOCKER_CALL_LOG="$TEST_ROOT/docker-calls.log" PATH="$fake_bin:$PATH" "$install_dir/goldenera" update >/dev/null
assert_contains "$TEST_ROOT/docker-calls.log" "compose up -d --remove-orphans"
assert_not_contains "$TEST_ROOT/docker-calls.log" "compose pull"

# Invalid mining configuration must fail before writing an installation.
if GOLDENERA_INSTALL_DIR="$TEST_ROOT/invalid" \
  GOLDENERA_P2P_HOST="203.0.113.12" \
  GOLDENERA_MINING_ENABLE=true \
  GOLDENERA_BENEFICIARY_ADDRESS="invalid" \
  "$ROOT_DIR/scripts/install.sh" --non-interactive --skip-docker-check >/dev/null 2>&1; then
  fail "invalid beneficiary address was accepted"
fi

printf 'GoldenEra installer smoke tests passed.\n'
