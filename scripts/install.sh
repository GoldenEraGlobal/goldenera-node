#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEFAULT_IMAGE="ghcr.io/goldeneraglobal/goldenera-node:latest"
readonly LOCAL_IMAGE="goldenera-node:sandbox-local"
readonly ZERO_ADDRESS="0x0000000000000000000000000000000000000000"
# Persistent pages cover one active FULL dataset/cache plus mining VM
# scratchpads. The entrypoint separately budgets standard-memory rollover.
readonly RANDOMX_DATASET_CACHE_HUGEPAGES=1168
readonly RANDOMX_HUGEPAGE_MARGIN=64
readonly RANDOMX_MIN_HUGEPAGES=1280
readonly RANDOMX_HUGEPAGE_GRANULARITY=64

INSTALL_DIR=""
NON_INTERACTIVE=false
SKIP_DOCKER_CHECK=false
RECONFIGURE=false
TTY_DEVICE="/dev/tty"

info() { printf '[goldenera] %s\n' "$*"; }
warn() { printf '[goldenera] WARNING: %s\n' "$*" >&2; }
die() { printf '[goldenera] ERROR: %s\n' "$*" >&2; exit 1; }
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

usage() {
  cat <<'EOF'
GoldenEra Node installer

Usage:
  install.sh [install] [options]
  install.sh reconfigure [options]

Options:
  --install-dir PATH       installation directory
  --image IMAGE            Docker image (default: public latest image)
  --local-image            use goldenera-node:sandbox-local without pulling
  --non-interactive        read configuration from GOLDENERA_* variables
  --skip-docker-check      intended only for installer tests
  --help

Non-interactive variables:
  GOLDENERA_INSTALL_DIR, GOLDENERA_IMAGE, GOLDENERA_NETWORK,
  GOLDENERA_P2P_HOST, GOLDENERA_P2P_PORT, GOLDENERA_API_PORT,
  GOLDENERA_MINING_ENABLE, GOLDENERA_BENEFICIARY_ADDRESS,
  GOLDENERA_MINING_THREADS, GOLDENERA_EXPLORER_ENABLE,
  GOLDENERA_IDENTITY_MNEMONIC, GOLDENERA_ADMIN_USERNAME,
  GOLDENERA_ADMIN_PASSWORD, GOLDENERA_POSTGRES_PASSWORD
EOF
}

is_true() {
  case "$(lower "$1")" in
    1|true|yes|y|on) return 0 ;;
    *) return 1 ;;
  esac
}

random_hex() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
  fi
}

random_base64() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "$bytes" | tr -d '\r\n'
  else
    dd if=/dev/urandom bs="$bytes" count=1 2>/dev/null | base64 | tr -d '\r\n'
  fi
}

prompt() {
  local target="$1" message="$2" default_value="${3:-}" input_value=""
  if "$NON_INTERACTIVE"; then
    printf -v "$target" '%s' "$default_value"
    return
  fi
  [ -r "$TTY_DEVICE" ] || die "No interactive terminal is available. Use --non-interactive."
  if [ -n "$default_value" ]; then
    printf '%s [%s]: ' "$message" "$default_value" >"$TTY_DEVICE"
  else
    printf '%s: ' "$message" >"$TTY_DEVICE"
  fi
  IFS= read -r input_value <"$TTY_DEVICE" || true
  printf -v "$target" '%s' "${input_value:-$default_value}"
}

prompt_secret() {
  local target="$1" message="$2" answer=""
  if "$NON_INTERACTIVE"; then
    printf -v "$target" '%s' "${3:-}"
    return
  fi
  printf '%s: ' "$message" >"$TTY_DEVICE"
  IFS= read -r -s answer <"$TTY_DEVICE" || true
  printf '\n' >"$TTY_DEVICE"
  printf -v "$target" '%s' "$answer"
}

prompt_yes_no() {
  local target="$1" message="$2" default_value="$3" answer=""
  if "$NON_INTERACTIVE"; then
    printf -v "$target" '%s' "$default_value"
    return
  fi
  while true; do
    prompt answer "$message (yes/no)" "$default_value"
    case "$(lower "$answer")" in
      y|yes|true) printf -v "$target" 'true'; return ;;
      n|no|false) printf -v "$target" 'false'; return ;;
      *) warn "Enter yes or no." ;;
    esac
  done
}

validate_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

validate_address() {
  [[ "$1" =~ ^0x[0-9a-fA-F]{40}$ ]]
}

validate_ipv4() {
  local ip="$1" octet=""
  local -a octets
  IFS=. read -r -a octets <<<"$ip"
  [ "${#octets[@]}" -eq 4 ] || return 1
  for octet in "${octets[@]}"; do
    [[ "$octet" =~ ^[0-9]{1,3}$ ]] || return 1
    [ "$octet" -le 255 ] || return 1
  done
  return 0
}

sudo_run() {
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi
}

wait_for_docker() {
  local attempts=60
  while [ "$attempts" -gt 0 ]; do
    if docker info >/dev/null 2>&1; then return 0; fi
    if [ "$(uname -s)" = Linux ] && sudo_run docker info >/dev/null 2>&1; then return 0; fi
    attempts=$((attempts - 1))
    sleep 2
  done
  return 1
}

install_docker_linux() {
  [ -r /etc/os-release ] || die "Unable to detect the Linux distribution. Install Docker manually."
  # shellcheck disable=SC1091
  . /etc/os-release
  case "${ID:-}" in
    ubuntu|debian) ;;
    *) die "Automatic Docker installation supports Ubuntu and Debian; detected: ${ID:-unknown}." ;;
  esac

  info "Installing Docker Engine from Docker's official apt repository..."
  sudo_run apt-get update
  sudo_run apt-get install -y ca-certificates curl
  sudo_run install -m 0755 -d /etc/apt/keyrings
  sudo_run curl -fsSL "https://download.docker.com/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
  sudo_run chmod a+r /etc/apt/keyrings/docker.asc

  local arch codename repository
  arch="$(dpkg --print-architecture)"
  codename="${VERSION_CODENAME:-}"
  [ -n "$codename" ] || die "The distribution does not provide VERSION_CODENAME. Install Docker manually."
  repository="deb [arch=${arch} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} ${codename} stable"
  printf '%s\n' "$repository" | sudo_run tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo_run apt-get update
  sudo_run apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo_run systemctl enable --now docker
  sudo_run usermod -aG docker "$USER" || true
}

install_homebrew() {
  if command -v brew >/dev/null 2>&1; then return; fi
  info "Installing Homebrew..."
  NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  if [ -x /opt/homebrew/bin/brew ]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
  elif [ -x /usr/local/bin/brew ]; then
    eval "$(/usr/local/bin/brew shellenv)"
  fi
  command -v brew >/dev/null 2>&1 || die "Homebrew was installed but is not in PATH. Open a new terminal and run the installer again."
}

install_docker_macos() {
  install_homebrew
  info "Installing Docker Desktop. Its use is subject to Docker's license terms."
  brew install --cask docker
  open -a Docker
}

ensure_docker() {
  "$SKIP_DOCKER_CHECK" && return
  local os
  os="$(uname -s)"
  if ! command -v docker >/dev/null 2>&1; then
    case "$os" in
      Linux) install_docker_linux ;;
      Darwin) install_docker_macos ;;
      *) die "This script supports Linux and macOS. Use install.ps1 on Windows." ;;
    esac
  elif [ "$os" = Darwin ] && ! docker info >/dev/null 2>&1; then
    open -a Docker || true
  elif [ "$os" = Linux ] && ! docker info >/dev/null 2>&1; then
    sudo_run systemctl start docker || true
  fi

  if ! wait_for_docker; then
    if [ "$os" = Linux ] && sudo_run docker info >/dev/null 2>&1; then
      warn "Docker works through sudo. Sign out and back in to use it without sudo."
    else
      die "The Docker daemon is not ready. Start Docker and run the installer again."
    fi
  fi
  docker compose version >/dev/null 2>&1 || sudo_run docker compose version >/dev/null 2>&1 \
    || die "The Docker Compose plugin is missing."
}

detect_public_ip() {
  command -v curl >/dev/null 2>&1 || return 0
  curl -4fsS --connect-timeout 3 --max-time 5 https://api.ipify.org 2>/dev/null || true
}

existing_env_value() {
  local key="$1" fallback="${2:-}" value=""
  if [ -f "$INSTALL_DIR/.env" ]; then
    value="$(sed -n "s/^${key}=//p" "$INSTALL_DIR/.env" | tail -n 1)"
  fi
  printf '%s' "${value:-$fallback}"
}

select_configuration() {
  local default_dir="$HOME/goldenera-node" dir_mode="automatic" detected_ip=""
  local identity_mode="automatic"

  if [ -n "${GOLDENERA_INSTALL_DIR:-}" ]; then
    INSTALL_DIR="$GOLDENERA_INSTALL_DIR"
  elif [ -n "$INSTALL_DIR" ]; then
    :
  elif "$NON_INTERACTIVE"; then
    INSTALL_DIR="$default_dir"
  else
    prompt dir_mode "Data location (automatic/manual)" "automatic"
    case "$(lower "$dir_mode")" in
      m|manual) prompt INSTALL_DIR "Absolute path" "$default_dir" ;;
      *) INSTALL_DIR="$default_dir" ;;
    esac
  fi
  [[ "$INSTALL_DIR" = /* ]] || die "The installation directory must be an absolute path: $INSTALL_DIR"

  NODE_IMAGE="${GOLDENERA_IMAGE:-${NODE_IMAGE:-$(existing_env_value GOLDENERA_IMAGE "$DEFAULT_IMAGE")}}"
  if [ "$NODE_IMAGE" = "$LOCAL_IMAGE" ] || is_true "${GOLDENERA_LOCAL_IMAGE:-false}"; then
    NODE_IMAGE="$LOCAL_IMAGE"
  fi
  prompt NODE_IMAGE "Docker image" "$NODE_IMAGE"
  if [ "$NODE_IMAGE" = "$LOCAL_IMAGE" ]; then
    PULL_POLICY="never"
  else
    PULL_POLICY="always"
  fi

  NETWORK="${GOLDENERA_NETWORK:-$(existing_env_value NETWORK MAINNET)}"
  if ! "$NON_INTERACTIVE"; then
    prompt NETWORK "Network (MAINNET/TESTNET)" "$NETWORK"
  fi
  NETWORK="$(upper "$NETWORK")"
  case "$NETWORK" in MAINNET|TESTNET) ;; *) die "Network must be MAINNET or TESTNET." ;; esac

  if [ -z "${GOLDENERA_P2P_HOST:-}" ]; then detected_ip="$(detect_public_ip)"; fi
  P2P_HOST="${GOLDENERA_P2P_HOST:-$(existing_env_value P2P_HOST "$detected_ip")}"
  prompt P2P_HOST "Public IPv4 address for P2P" "$P2P_HOST"
  [ -n "$P2P_HOST" ] || die "P2P host is required."
  validate_ipv4 "$P2P_HOST" || die "P2P host must be a valid IPv4 address."

  P2P_PORT="${GOLDENERA_P2P_PORT:-$(existing_env_value P2P_PORT 9000)}"
  API_PORT="${GOLDENERA_API_PORT:-$(existing_env_value LISTEN_PORT 8080)}"
  prompt P2P_PORT "P2P port" "$P2P_PORT"
  prompt API_PORT "API/Explorer port" "$API_PORT"
  validate_port "$P2P_PORT" || die "Invalid P2P port: $P2P_PORT"
  validate_port "$API_PORT" || die "Invalid API port: $API_PORT"
  [ "$P2P_PORT" != "$API_PORT" ] || die "P2P and API ports must be different."

  MINING_ENABLE="${GOLDENERA_MINING_ENABLE:-$(existing_env_value MINING_ENABLE false)}"
  prompt_yes_no MINING_ENABLE "Enable mining" "$MINING_ENABLE"
  BENEFICIARY_ADDRESS="${GOLDENERA_BENEFICIARY_ADDRESS:-$(existing_env_value BENEFICIARY_ADDRESS "$ZERO_ADDRESS")}"
  MINING_THREADS="${GOLDENERA_MINING_THREADS:-$(existing_env_value MINING_HASHING_THREADS -1)}"
  if is_true "$MINING_ENABLE"; then
    prompt BENEFICIARY_ADDRESS "Reward address (0x...)" "$BENEFICIARY_ADDRESS"
    validate_address "$BENEFICIARY_ADDRESS" || die "The reward address must be 0x followed by 40 hexadecimal characters."
    [ "$BENEFICIARY_ADDRESS" != "$ZERO_ADDRESS" ] || die "The reward address must not be the zero address when mining is enabled."
    prompt MINING_THREADS "Mining threads (-1 = automatic)" "$MINING_THREADS"
    [[ "$MINING_THREADS" =~ ^-1$|^[1-9][0-9]*$ ]] || die "Mining threads must be -1 or a positive integer."
  fi

  EXPLORER_ENABLE="${GOLDENERA_EXPLORER_ENABLE:-$(existing_env_value EXPLORER_ENABLE true)}"
  prompt_yes_no EXPLORER_ENABLE "Enable the built-in Explorer/indexer, PostgreSQL, and webhooks" "$EXPLORER_ENABLE"

  IDENTITY_MNEMONIC="${GOLDENERA_IDENTITY_MNEMONIC:-}"
  if [ -z "$IDENTITY_MNEMONIC" ] && ! "$NON_INTERACTIVE"; then
    prompt identity_mode "Node identity (automatic/import mnemonic)" "automatic"
    case "$(lower "$identity_mode")" in
      i|import|mnemonic) prompt_secret IDENTITY_MNEMONIC "Node identity mnemonic (input is hidden)" ;;
    esac
  fi
  if [ -n "$IDENTITY_MNEMONIC" ]; then
    [ "$(printf '%s' "$IDENTITY_MNEMONIC" | wc -w | tr -d ' ')" -ge 12 ] \
      || die "The node identity mnemonic appears invalid (fewer than 12 words)."
  fi

  ADMIN_USERNAME="${GOLDENERA_ADMIN_USERNAME:-$(existing_env_value ADMIN_USERNAME admin)}"
  ADMIN_PASSWORD="${GOLDENERA_ADMIN_PASSWORD:-$(existing_env_value ADMIN_PASSWORD "$(random_hex 16)")}"
  POSTGRES_PASSWORD="${GOLDENERA_POSTGRES_PASSWORD:-$(existing_env_value POSTGRESQL_PASSWORD "$(random_hex 16)")}"
  HMAC_SECRET="${GOLDENERA_HMAC_SECRET:-$(existing_env_value SECURITY_HMAC_SECRET "$(random_base64 32)")}"
  AES_SECRET="${GOLDENERA_AES_SECRET:-$(existing_env_value SECURITY_AES_GCM_SECRET "$(random_base64 32)")}"
}

write_compose() {
  local compose_file="$INSTALL_DIR/compose.yaml"
  cat >"$compose_file" <<'EOF'
name: goldenera
services:
  node:
    image: ${GOLDENERA_IMAGE}
    pull_policy: ${GOLDENERA_PULL_POLICY}
    restart: unless-stopped
    env_file: [.env]
    environment:
      POSTGRESQL_HOST: db
      LOGGING_FILE: ${LOGGING_FILE:-goldenera.log}
    ports:
      - "${LISTEN_PORT:-8080}:${LISTEN_PORT:-8080}"
      - "${P2P_PORT:-9000}:${P2P_PORT:-9000}"
    volumes:
      - ./node_data:/app/node_data
      - ./node_logs:/app/node_logs
    cap_add:
      - IPC_LOCK
    ulimits:
      memlock:
        soft: -1
        hard: -1
EOF
  if is_true "$EXPLORER_ENABLE"; then
    cat >>"$compose_file" <<'EOF'
    depends_on:
      db:
        condition: service_healthy

  db:
    image: postgres:18.1-alpine
    restart: unless-stopped
    env_file: [.env]
    command: postgres -c shared_buffers=512MB -c max_connections=100
    environment:
      POSTGRES_DB: ${POSTGRESQL_DB_NAME:-node_db}
      POSTGRES_USER: ${POSTGRESQL_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${POSTGRESQL_PASSWORD}
    volumes:
      - ./postgres_data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRESQL_USERNAME:-postgres}"]
      interval: 5s
      timeout: 5s
      retries: 12
EOF
  fi
}

write_env() {
  local env_file="$INSTALL_DIR/.env"
  umask 077
  cat >"$env_file" <<EOF
GOLDENERA_IMAGE=$NODE_IMAGE
GOLDENERA_PULL_POLICY=$PULL_POLICY
SPRING_PROFILES_ACTIVE=prod
LISTEN_PORT=$API_PORT
NETWORK=$NETWORK
BENEFICIARY_ADDRESS=$BENEFICIARY_ADDRESS
P2P_HOST=$P2P_HOST
P2P_PORT=$P2P_PORT
NODE_IDENTITY_FILE=./node_data/.node_identity
BLOCKCHAIN_DB_PATH=./node_data/blockchain
PEER_REPUTATION_DB_PATH=./node_data/peer-reputation
MINING_ENABLE=$MINING_ENABLE
MINING_HASHING_THREADS=$MINING_THREADS
MINING_MEMORY_MODE=FULL
POSTGRESQL_ENABLE=$EXPLORER_ENABLE
EXPLORER_ENABLE=$EXPLORER_ENABLE
WEBHOOK_ENABLE=$EXPLORER_ENABLE
POSTGRESQL_PORT=5432
POSTGRESQL_DB_NAME=node_db
POSTGRESQL_USERNAME=postgres
POSTGRESQL_PASSWORD=$POSTGRES_PASSWORD
SECURITY_HMAC_SECRET=$HMAC_SECRET
SECURITY_AES_GCM_SECRET=$AES_SECRET
SECURITY_CORE_API_ENABLED=false
SECURITY_EXPLORER_API_ENABLED=$EXPLORER_ENABLE
ADMIN_USERNAME=$ADMIN_USERNAME
ADMIN_PASSWORD=$ADMIN_PASSWORD
JAVA_HEAP_MB=
ROCKSDB_BLOCK_CACHE_MB=512
ROCKSDB_WRITE_BUFFER_MB=64
ROCKSDB_MAX_WRITE_BUFFERS=4
ROCKSDB_MAX_BACKGROUND_JOBS=6
ROCKSDB_BLOCK_SIZE_KB=16
ROCKSDB_BLOOM_FILTER_BITS=10
ROCKSDB_DIRECT_READS=true
ROCKSDB_DIRECT_WRITES=true
ROCKSDB_RATE_LIMIT_MB_PER_SEC=0
ROCKSDB_BLOB_ENABLED=true
ROCKSDB_BLOB_MIN_BYTES=65536
ROCKSDB_BLOB_FILE_SIZE_MB=256
ROCKSDB_BLOB_GC_ENABLED=true
ROCKSDB_BLOB_GC_AGE_CUTOFF=0.25
CACHE_BLOCK_MB=256
CACHE_TRIE_NODE_MB=256
CACHE_TX_MB=128
CACHE_HEADER_MAX_ENTRIES=50000
CACHE_HEIGHT_MAX_ENTRIES=100000
CACHE_EXPIRE_MINUTES=60
DIRECTORY_PING_INTERVAL_IN_MS=30000
MEMPOOL_MAX_SIZE=100000
MEMPOOL_EXPIRE_TX_IN_MINUTES=60
MEMPOOL_MIN_ACCEPTABLE_FEE_IN_WEI=10
MEMPOOL_MAX_NONCE_GAP_PER_SENDER=64
LOGGING_DIR=./node_logs
LOGGING_FILE=goldenera.log
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_GLOBAL_GOLDENERA=INFO
THROTTLING_GLOBAL_CAPACITY=500
THROTTLING_GLOBAL_REFILL_TOKENS=500
THROTTLING_PUBLIC_CORE_CAPACITY=100
THROTTLING_PUBLIC_CORE_REFILL_TOKENS=20
THROTTLING_API_KEY_DEFAULT_CAPACITY=5000
THROTTLING_API_KEY_DEFAULT_REFILL_TOKENS=2000
THROTTLING_API_KEY_EXPLORER_CAPACITY=500
THROTTLING_API_KEY_EXPLORER_REFILL_TOKENS=100
THROTTLING_P2P_CAPACITY=20000
THROTTLING_P2P_REFILL_TOKENS=10000
EOF
  chmod 600 "$env_file"
}

write_controller() {
  cat >"$INSTALL_DIR/goldenera" <<'EOF'
#!/usr/bin/env sh
set -eu
INSTALL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$INSTALL_DIR"

dc() {
  if docker info >/dev/null 2>&1; then docker compose "$@"; else sudo docker compose "$@"; fi
}

command_name=${1:-status}
case "$command_name" in
  start) dc up -d --remove-orphans ;;
  stop) dc stop ;;
  restart) dc restart node ;;
  update)
    pull_policy=$(sed -n 's/^GOLDENERA_PULL_POLICY=//p' .env | tail -n 1)
    if [ "$pull_policy" != never ]; then dc pull; fi
    dc up -d --remove-orphans
    dc ps
    ;;
  status) dc ps ;;
  logs) shift; dc logs -f "${1:-node}" ;;
  config) dc config ;;
  *)
    echo "Usage: $0 {start|stop|restart|update|status|logs [node|db]|config}" >&2
    exit 2
    ;;
esac
EOF
  chmod 700 "$INSTALL_DIR/goldenera"
}

write_identity() {
  [ -n "$IDENTITY_MNEMONIC" ] || return 0
  umask 077
  printf '%s' "$IDENTITY_MNEMONIC" >"$INSTALL_DIR/node_data/.node_identity"
  chmod 600 "$INSTALL_DIR/node_data/.node_identity"
}

resolve_mining_workers() {
  local configured_threads="$1" processors="$2"
  if [[ "$configured_threads" =~ ^[1-9][0-9]*$ ]]; then
    printf '%s' "$configured_threads"
  elif [ "$processors" -gt 2 ]; then
    printf '%s' "$((processors - 2))"
  else
    printf '1'
  fi
}

calculate_randomx_hugepages() {
  local workers="$1" pages
  pages=$((RANDOMX_DATASET_CACHE_HUGEPAGES + workers + RANDOMX_HUGEPAGE_MARGIN))
  [ "$pages" -ge "$RANDOMX_MIN_HUGEPAGES" ] || pages="$RANDOMX_MIN_HUGEPAGES"
  pages=$((((pages + RANDOMX_HUGEPAGE_GRANULARITY - 1) / RANDOMX_HUGEPAGE_GRANULARITY)
    * RANDOMX_HUGEPAGE_GRANULARITY))
  printf '%s' "$pages"
}

read_linux_hugepage_count() {
  local key="$1" meminfo_path="${2:-/proc/meminfo}" value
  value="$(awk -v key="${key}:" '$1 == key { print $2; exit }' "$meminfo_path" 2>/dev/null || true)"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$value"
}

tune_linux_hugepages() {
  local processors workers hugepages actual_total actual_free
  is_true "$MINING_ENABLE" || return 0
  [ "$(uname -s)" = Linux ] || return 0
  if "$SKIP_DOCKER_CHECK"; then return 0; fi
  processors="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1')"
  [[ "$processors" =~ ^[1-9][0-9]*$ ]] || processors=1
  workers="$(resolve_mining_workers "$MINING_THREADS" "$processors")"
  hugepages="$(calculate_randomx_hugepages "$workers")"
  info "Configuring ${hugepages} huge pages for RandomX mining (${workers} workers)..."
  printf 'vm.nr_hugepages=%s\n' "$hugepages" | sudo_run tee /etc/sysctl.d/99-goldenera-node.conf >/dev/null
  sudo_run sysctl --system >/dev/null
  actual_total="$(read_linux_hugepage_count HugePages_Total || printf '0')"
  actual_free="$(read_linux_hugepage_count HugePages_Free || printf '0')"
  if [ "$actual_total" -lt "$hugepages" ] || [ "$actual_free" -lt "$RANDOMX_MIN_HUGEPAGES" ]; then
    warn "The kernel reserved ${actual_total}/${hugepages} requested huge pages (${actual_free} free)."
    warn "RandomX will safely use standard memory until at least ${RANDOMX_MIN_HUGEPAGES} huge pages are free."
  else
    info "Huge-page reservation ready: ${actual_total} total, ${actual_free} free."
  fi
}

install_node() {
  if [ -e "$INSTALL_DIR/.env" ] && ! "$RECONFIGURE"; then
    die "An installation already exists in $INSTALL_DIR. Use ./goldenera update there or run install.sh reconfigure."
  fi
  mkdir -p "$INSTALL_DIR/node_data" "$INSTALL_DIR/node_logs" "$INSTALL_DIR/postgres_data"
  write_compose
  write_env
  write_controller
  write_identity
  tune_linux_hugepages

  if ! "$SKIP_DOCKER_CHECK"; then
    (cd "$INSTALL_DIR" && if docker info >/dev/null 2>&1; then docker compose config --quiet && docker compose up -d --remove-orphans; else sudo docker compose config --quiet && sudo docker compose up -d --remove-orphans; fi)
  fi

  info "GoldenEra Node is configured in $INSTALL_DIR"
  info "Manage it with: $INSTALL_DIR/goldenera {status|logs|update|restart|stop|start}"
  info "API/Explorer: http://localhost:$API_PORT"
  info "Admin username: $ADMIN_USERNAME"
  info "Admin password: $ADMIN_PASSWORD"
  warn "Save the admin password in a password manager; .env contains sensitive values."
}

main() {
  local command_name="install"
  NODE_IMAGE=""
  PULL_POLICY=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      install) command_name="install" ;;
      reconfigure) command_name="reconfigure"; RECONFIGURE=true ;;
      --install-dir) [ "$#" -ge 2 ] || die "--install-dir requires a value"; INSTALL_DIR="$2"; shift ;;
      --image) [ "$#" -ge 2 ] || die "--image requires a value"; NODE_IMAGE="$2"; shift ;;
      --local-image) NODE_IMAGE="$LOCAL_IMAGE" ;;
      --non-interactive) NON_INTERACTIVE=true ;;
      --skip-docker-check) SKIP_DOCKER_CHECK=true ;;
      --help|-h) usage; exit 0 ;;
      *) die "Unknown argument: $1" ;;
    esac
    shift
  done
  [ "$command_name" = install ] || RECONFIGURE=true
  ensure_docker
  select_configuration
  install_node
}

if [ "${GOLDENERA_INSTALLER_LIBRARY_ONLY:-false}" != true ]; then
  main "$@"
fi
