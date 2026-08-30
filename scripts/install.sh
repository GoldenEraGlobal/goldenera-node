#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEFAULT_IMAGE="ghcr.io/goldeneraglobal/goldenera-node:latest"
readonly LOCAL_IMAGE="goldenera-node:sandbox-local"
readonly ZERO_ADDRESS="0x0000000000000000000000000000000000000000"
# Persistent pages cover one active FULL dataset/cache plus mining VM
# scratchpads. The entrypoint separately budgets optional sync acceleration and
# retained verification caches.
readonly RANDOMX_DATASET_CACHE_HUGEPAGES=1168
readonly RANDOMX_HUGEPAGE_MARGIN=64
readonly RANDOMX_MIN_HUGEPAGES=1280
readonly RANDOMX_HUGEPAGE_GRANULARITY=16

INSTALL_DIR=""
NON_INTERACTIVE=false
SKIP_DOCKER_CHECK=false
RECONFIGURE=false
TTY_DEVICE="/dev/tty"
INSTALL_MODE=""
USE_ANSI=false
USE_COLOR=false

if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ]; then
  USE_ANSI=true
fi
if "$USE_ANSI" && [ -z "${NO_COLOR:-}" ]; then USE_COLOR=true; fi

if "$USE_COLOR"; then
  readonly COLOR_GOLD=$'\033[38;5;220m'
  readonly COLOR_GREEN=$'\033[32m'
  readonly COLOR_RED=$'\033[31m'
  readonly COLOR_DIM=$'\033[2m'
  readonly COLOR_BOLD=$'\033[1m'
  readonly COLOR_RESET=$'\033[0m'
else
  readonly COLOR_GOLD="" COLOR_GREEN="" COLOR_RED="" COLOR_DIM="" COLOR_BOLD="" COLOR_RESET=""
fi

info() { printf '%s[goldenera]%s %s\n' "$COLOR_GREEN" "$COLOR_RESET" "$*"; }
warn() { printf '%s[goldenera] WARNING:%s %s\n' "$COLOR_GOLD" "$COLOR_RESET" "$*" >&2; }
die() { printf '%s[goldenera] ERROR:%s %s\n' "$COLOR_RED" "$COLOR_RESET" "$*" >&2; exit 1; }
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

print_banner() {
  "$NON_INTERACTIVE" && return
  printf '\n%s%sGoldenEra Node%s\n' "$COLOR_GOLD" "$COLOR_BOLD" "$COLOR_RESET"
  printf '%sSecure node installer%s\n\n' "$COLOR_DIM" "$COLOR_RESET"
}

print_section() {
  "$NON_INTERACTIVE" && return
  printf '\n%s%s%s%s\n' "$COLOR_BOLD" "$COLOR_GOLD" "$1" "$COLOR_RESET"
}

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
  --mode MODE              automatic or manual interactive configuration
  --non-interactive        read configuration from GOLDENERA_* variables
  --skip-docker-check      intended only for installer tests
  --help

Non-interactive variables:
  GOLDENERA_INSTALL_MODE, GOLDENERA_INSTALL_DIR, GOLDENERA_IMAGE, GOLDENERA_NETWORK,
  GOLDENERA_P2P_HOST, GOLDENERA_P2P_PORT, GOLDENERA_API_PORT,
  GOLDENERA_MINING_ENABLE, GOLDENERA_BENEFICIARY_ADDRESS,
  GOLDENERA_MINING_THREADS, GOLDENERA_CONFIGURE_HUGEPAGES,
  GOLDENERA_NODE_MEMORY_LIMIT_MB, GOLDENERA_EXPLORER_ENABLE,
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
    printf '%s›%s %s %s[%s]%s: ' "$COLOR_GOLD" "$COLOR_RESET" "$message" "$COLOR_DIM" "$default_value" "$COLOR_RESET" >"$TTY_DEVICE"
  else
    printf '%s›%s %s: ' "$COLOR_GOLD" "$COLOR_RESET" "$message" >"$TTY_DEVICE"
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
  [ -r "$TTY_DEVICE" ] || die "No interactive terminal is available. Use --non-interactive."
  printf '%s›%s %s: ' "$COLOR_GOLD" "$COLOR_RESET" "$message" >"$TTY_DEVICE"
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
  [ -r "$TTY_DEVICE" ] || die "No interactive terminal is available. Use --non-interactive."
  while true; do
    printf '%s›%s %s %s(y/N)%s: ' "$COLOR_GOLD" "$COLOR_RESET" "$message" "$COLOR_BOLD" "$COLOR_RESET" >"$TTY_DEVICE"
    IFS= read -r answer <"$TTY_DEVICE" || true
    case "$(lower "$answer")" in
      y|yes|true) printf -v "$target" 'true'; return ;;
      ""|n|no|false) printf -v "$target" 'false'; return ;;
      *) warn "Enter y or n (yes/no, true/false are also supported)." ;;
    esac
  done
}

prompt_choice() {
  local target="$1" message="$2" default_index="$3"
  shift 3
  local -a items=("$@")
  local selected="$default_index" key="" escape="" index label value letter

  [ -r "$TTY_DEVICE" ] || die "No interactive terminal is available. Use --non-interactive."
  printf '%s?%s %s%s%s\n' "$COLOR_GOLD" "$COLOR_RESET" "$COLOR_BOLD" "$message" "$COLOR_RESET" >"$TTY_DEVICE"
  for index in "${!items[@]}"; do
    label="${items[$index]%%|*}"
    printf '  %s%s)%s %s\n' "$COLOR_GOLD" "$(printf "\\$(printf '%03o' "$((65 + index))")")" "$COLOR_RESET" "$label" >"$TTY_DEVICE"
  done

  if "$USE_ANSI"; then
    while true; do
      label="${items[$selected]%%|*}"
      letter="$(printf "\\$(printf '%03o' "$((65 + selected))")")"
      printf '\r\033[2K%s›%s Use ↑/↓ or A-%s, then Enter: %s%s — %s%s' \
        "$COLOR_GOLD" "$COLOR_RESET" \
        "$(printf "\\$(printf '%03o' "$((64 + ${#items[@]}))")")" \
        "$COLOR_BOLD" "$letter" "$label" "$COLOR_RESET" >"$TTY_DEVICE"
      IFS= read -r -s -n 1 key <"$TTY_DEVICE" || true
      case "$key" in
        "") break ;;
        $'\033')
          escape=""
          IFS= read -r -s -n 2 -t 0.2 escape <"$TTY_DEVICE" || true
          case "$escape" in
            '[A') selected=$(((selected - 1 + ${#items[@]}) % ${#items[@]})) ;;
            '[B') selected=$(((selected + 1) % ${#items[@]})) ;;
          esac
          ;;
        *)
          key="$(upper "$key")"
          if [[ "$key" =~ ^[A-Z]$ ]]; then
            index=$(($(printf '%d' "'$key") - 65))
            if [ "$index" -ge 0 ] && [ "$index" -lt "${#items[@]}" ]; then
              selected="$index"
            fi
          fi
          ;;
      esac
    done
    printf '\n' >"$TTY_DEVICE"
  else
    while true; do
      letter="$(printf "\\$(printf '%03o' "$((65 + default_index))")")"
      prompt key "Choose A-$(printf "\\$(printf '%03o' "$((64 + ${#items[@]}))")")" "$letter"
      key="$(upper "$key")"
      [[ "$key" =~ ^[A-Z]$ ]] || { warn "Choose one of the listed letters."; continue; }
      index=$(($(printf '%d' "'$key") - 65))
      if [ "$index" -ge 0 ] && [ "$index" -lt "${#items[@]}" ]; then
        selected="$index"
        break
      fi
      warn "Choose one of the listed letters."
    done
  fi

  value="${items[$selected]#*|}"
  printf -v "$target" '%s' "$value"
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

select_install_mode() {
  if "$NON_INTERACTIVE"; then
    INSTALL_MODE="non-interactive"
    return
  fi

  INSTALL_MODE="${INSTALL_MODE:-${GOLDENERA_INSTALL_MODE:-}}"
  case "$(lower "$INSTALL_MODE")" in
    a|automatic) INSTALL_MODE="automatic" ;;
    b|manual) INSTALL_MODE="manual" ;;
    "")
      print_banner
      prompt_choice INSTALL_MODE "How would you like to configure the node?" 0 \
        "Automatic — miner defaults, without Explorer; asks for reward address|automatic" \
        "Manual — review and customize every setting|manual"
      ;;
    *) die "Installation mode must be automatic or manual." ;;
  esac
}

select_configuration() {
  local default_dir="$HOME/goldenera-node" detected_ip="" identity_mode="automatic"
  local image_mode="recommended" network_mode="mainnet" image_preconfigured=false
  local manual_configuration=false mining_fallback=false
  if [ "$INSTALL_MODE" = manual ]; then manual_configuration=true; fi

  print_section "Installation"

  if [ -n "${GOLDENERA_INSTALL_DIR:-}" ]; then
    INSTALL_DIR="$GOLDENERA_INSTALL_DIR"
  elif [ -n "$INSTALL_DIR" ]; then
    :
  elif "$NON_INTERACTIVE"; then
    INSTALL_DIR="$default_dir"
  elif "$manual_configuration"; then
    prompt INSTALL_DIR "Installation directory" "$default_dir"
  else
    INSTALL_DIR="$default_dir"
  fi
  [[ "$INSTALL_DIR" = /* ]] || die "The installation directory must be an absolute path: $INSTALL_DIR"

  if [ -n "$NODE_IMAGE" ] || [ -n "${GOLDENERA_IMAGE:-}" ] || is_true "${GOLDENERA_LOCAL_IMAGE:-false}"; then
    image_preconfigured=true
  fi
  NODE_IMAGE="${GOLDENERA_IMAGE:-${NODE_IMAGE:-$(existing_env_value GOLDENERA_IMAGE "$DEFAULT_IMAGE")}}"
  if [ "$NODE_IMAGE" = "$LOCAL_IMAGE" ] || is_true "${GOLDENERA_LOCAL_IMAGE:-false}"; then
    NODE_IMAGE="$LOCAL_IMAGE"
  fi
  if "$manual_configuration" && ! "$image_preconfigured"; then
    [ "$NODE_IMAGE" = "$DEFAULT_IMAGE" ] || image_mode="custom"
    if [ "$image_mode" = custom ]; then
      prompt_choice image_mode "Which Docker image would you like to use?" 1 \
        "Recommended — $DEFAULT_IMAGE|recommended" "Custom image|custom"
    else
      prompt_choice image_mode "Which Docker image would you like to use?" 0 \
        "Recommended — $DEFAULT_IMAGE|recommended" "Custom image|custom"
    fi
    if [ "$image_mode" = custom ]; then
      if [ "$NODE_IMAGE" = "$DEFAULT_IMAGE" ]; then
        prompt NODE_IMAGE "Custom Docker image" ""
      else
        prompt NODE_IMAGE "Custom Docker image" "$NODE_IMAGE"
      fi
      [ -n "$NODE_IMAGE" ] || die "A custom Docker image is required."
    else
      NODE_IMAGE="$DEFAULT_IMAGE"
    fi
  fi
  if [ "$NODE_IMAGE" = "$LOCAL_IMAGE" ]; then
    PULL_POLICY="never"
  else
    PULL_POLICY="always"
  fi

  NETWORK="${GOLDENERA_NETWORK:-$(existing_env_value NETWORK MAINNET)}"
  if "$manual_configuration"; then
    if [ "$(upper "$NETWORK")" = TESTNET ]; then network_mode="testnet"; fi
    if [ "$network_mode" = testnet ]; then
      prompt_choice network_mode "Select network" 1 "MAINNET|mainnet" "TESTNET|testnet"
    else
      prompt_choice network_mode "Select network" 0 "MAINNET|mainnet" "TESTNET|testnet"
    fi
    NETWORK="$network_mode"
  fi
  NETWORK="$(upper "$NETWORK")"
  case "$NETWORK" in MAINNET|TESTNET) ;; *) die "Network must be MAINNET or TESTNET." ;; esac

  print_section "Connectivity"
  if [ -z "${GOLDENERA_P2P_HOST:-}" ]; then detected_ip="$(detect_public_ip)"; fi
  P2P_HOST="${GOLDENERA_P2P_HOST:-$(existing_env_value P2P_HOST "$detected_ip")}"
  if "$manual_configuration" || { [ "$INSTALL_MODE" = automatic ] && [ -z "$P2P_HOST" ]; }; then
    prompt P2P_HOST "Public IPv4 address for P2P" "$P2P_HOST"
  fi
  [ -n "$P2P_HOST" ] || die "P2P host is required. Set GOLDENERA_P2P_HOST or use manual mode."
  validate_ipv4 "$P2P_HOST" || die "P2P host must be a valid IPv4 address."

  EXPLORER_ENABLE="${GOLDENERA_EXPLORER_ENABLE:-$(existing_env_value EXPLORER_ENABLE false)}"
  if "$manual_configuration"; then
    prompt_yes_no EXPLORER_ENABLE "Enable the built-in Explorer/indexer, PostgreSQL, and webhooks" "$EXPLORER_ENABLE"
  fi

  P2P_PORT="${GOLDENERA_P2P_PORT:-$(existing_env_value P2P_PORT 9000)}"
  API_PORT="${GOLDENERA_API_PORT:-$(existing_env_value LISTEN_PORT 8080)}"
  if "$manual_configuration"; then
    prompt P2P_PORT "P2P port" "$P2P_PORT"
  fi
  if "$manual_configuration" && is_true "$EXPLORER_ENABLE"; then
    prompt API_PORT "API/Explorer port" "$API_PORT"
  fi
  validate_port "$P2P_PORT" || die "Invalid P2P port: $P2P_PORT"
  validate_port "$API_PORT" || die "Invalid API port: $API_PORT"
  [ "$P2P_PORT" != "$API_PORT" ] || die "P2P and API ports must be different."

  print_section "Mining"
  if [ "$INSTALL_MODE" = automatic ]; then mining_fallback=true; fi
  MINING_ENABLE="${GOLDENERA_MINING_ENABLE:-$(existing_env_value MINING_ENABLE "$mining_fallback")}"
  if "$manual_configuration"; then
    prompt_yes_no MINING_ENABLE "Enable mining" "$MINING_ENABLE"
  fi
  BENEFICIARY_ADDRESS="${GOLDENERA_BENEFICIARY_ADDRESS:-$(existing_env_value BENEFICIARY_ADDRESS "$ZERO_ADDRESS")}"
  MINING_THREADS="${GOLDENERA_MINING_THREADS:-$(existing_env_value MINING_HASHING_THREADS -1)}"
  if is_true "$MINING_ENABLE"; then
    if "$manual_configuration" || [ "$BENEFICIARY_ADDRESS" = "$ZERO_ADDRESS" ]; then
      if [ "$BENEFICIARY_ADDRESS" = "$ZERO_ADDRESS" ]; then
        prompt BENEFICIARY_ADDRESS "Mining reward address (0x...)" ""
      else
        prompt BENEFICIARY_ADDRESS "Mining reward address (0x...)" "$BENEFICIARY_ADDRESS"
      fi
    fi
    validate_address "$BENEFICIARY_ADDRESS" || die "The reward address must be 0x followed by 40 hexadecimal characters."
    [ "$BENEFICIARY_ADDRESS" != "$ZERO_ADDRESS" ] || die "The reward address must not be the zero address when mining is enabled."
    if "$manual_configuration"; then
      prompt MINING_THREADS "Mining threads (-1 = automatic)" "$MINING_THREADS"
    fi
    [[ "$MINING_THREADS" =~ ^-1$|^[1-9][0-9]*$ ]] || die "Mining threads must be -1 or a positive integer."
  fi

  CONFIGURE_HUGEPAGES="${GOLDENERA_CONFIGURE_HUGEPAGES:-$(existing_env_value CONFIGURE_RANDOMX_HUGEPAGES false)}"
  if "$manual_configuration" && is_true "$MINING_ENABLE" && [ "$(uname -s)" = Linux ]; then
    prompt_yes_no CONFIGURE_HUGEPAGES \
      "Reserve RandomX huge pages (at least 1280 pages, usually 2.5GB host RAM)" "$CONFIGURE_HUGEPAGES"
  elif ! is_true "$MINING_ENABLE" || [ "$(uname -s)" != Linux ]; then
    CONFIGURE_HUGEPAGES=false
  fi

  local default_node_memory_mb=8192 minimum_node_memory_mb=8192
  if is_true "$MINING_ENABLE"; then
    default_node_memory_mb=12288
    minimum_node_memory_mb=12288
    if is_true "$CONFIGURE_HUGEPAGES"; then
      default_node_memory_mb=9216
      minimum_node_memory_mb=9216
    fi
  fi
  NODE_MEMORY_LIMIT_MB="${GOLDENERA_NODE_MEMORY_LIMIT_MB:-$(existing_env_value NODE_MEMORY_LIMIT_MB "$default_node_memory_mb")}"
  if "$manual_configuration"; then
    prompt NODE_MEMORY_LIMIT_MB "Node container memory limit in MB" "$NODE_MEMORY_LIMIT_MB"
  fi
  [[ "$NODE_MEMORY_LIMIT_MB" =~ ^[1-9][0-9]*$ ]] || die "Node memory limit must be a positive integer."
  [ "$NODE_MEMORY_LIMIT_MB" -ge "$minimum_node_memory_mb" ] \
    || die "This profile requires a node memory limit of at least ${minimum_node_memory_mb} MB."
  POSTGRESQL_MEMORY_LIMIT_MB=1024
  IDENTITY_MNEMONIC="${GOLDENERA_IDENTITY_MNEMONIC:-}"
  if [ -z "$IDENTITY_MNEMONIC" ] && "$manual_configuration"; then
    prompt_choice identity_mode "Node identity" 0 \
      "Automatic — create a new identity|automatic" \
      "Import an existing mnemonic|import"
    if [ "$identity_mode" = import ]; then
      prompt_secret IDENTITY_MNEMONIC "Node identity mnemonic (input is hidden)"
    fi
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
    mem_limit: ${NODE_MEMORY_LIMIT_MB}m
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
    mem_limit: ${POSTGRESQL_MEMORY_LIMIT_MB:-1024}m
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
CONFIGURE_RANDOMX_HUGEPAGES=$CONFIGURE_HUGEPAGES
NODE_MEMORY_LIMIT_MB=$NODE_MEMORY_LIMIT_MB
POSTGRESQL_MEMORY_LIMIT_MB=$POSTGRESQL_MEMORY_LIMIT_MB
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
JAVA_HEAP_MB=$(existing_env_value JAVA_HEAP_MB "")
JAVA_INITIAL_HEAP_MB=1024
JAVA_NMT_LEVEL=summary
JAVA_JFR_ENABLE=true
ROCKSDB_BLOCK_CACHE_MB=512
ROCKSDB_WRITE_BUFFER_MB=32
ROCKSDB_MAX_WRITE_BUFFERS=2
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
SYNC_RANDOMX_VERIFICATION_MODE=LIGHT
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
  local processors workers hugepages actual_total actual_free host_memory_mb hugepage_size_kb
  local hugepage_reserve_mb postgres_reserve_mb required_host_mb
  is_true "$MINING_ENABLE" || return 0
  is_true "$CONFIGURE_HUGEPAGES" || {
    info "Huge-page tuning skipped; enable it explicitly with GOLDENERA_CONFIGURE_HUGEPAGES=true."
    return 0
  }
  [ "$(uname -s)" = Linux ] || return 0
  if "$SKIP_DOCKER_CHECK"; then return 0; fi
  processors="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1')"
  [[ "$processors" =~ ^[1-9][0-9]*$ ]] || processors=1
  workers="$(resolve_mining_workers "$MINING_THREADS" "$processors")"
  hugepages="$(calculate_randomx_hugepages "$workers")"
  host_memory_mb="$(awk '$1 == "MemTotal:" { print int($2 / 1024); exit }' /proc/meminfo)"
  hugepage_size_kb="$(awk '$1 == "Hugepagesize:" { print $2; exit }' /proc/meminfo)"
  [[ "$host_memory_mb" =~ ^[1-9][0-9]*$ ]] || die "Unable to detect host memory before huge-page tuning."
  [[ "$hugepage_size_kb" =~ ^[1-9][0-9]*$ ]] || die "Unable to detect the huge-page size."
  hugepage_reserve_mb=$((hugepages * hugepage_size_kb / 1024))
  postgres_reserve_mb=0
  is_true "$EXPLORER_ENABLE" && postgres_reserve_mb="$POSTGRESQL_MEMORY_LIMIT_MB"
  required_host_mb=$((NODE_MEMORY_LIMIT_MB + hugepage_reserve_mb + postgres_reserve_mb + 1024))
  [ "$host_memory_mb" -ge "$required_host_mb" ] \
    || die "Huge-page profile requires at least ${required_host_mb} MB host RAM; detected ${host_memory_mb} MB."
  info "Configuring ${hugepages} huge pages for RandomX mining (${workers} workers)..."
  printf 'vm.nr_hugepages=%s\n' "$hugepages" | sudo_run tee /etc/sysctl.d/99-goldenera-node.conf >/dev/null
  sudo_run sysctl -w "vm.nr_hugepages=$hugepages" >/dev/null
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
  if is_true "$EXPLORER_ENABLE"; then
    info "API/Explorer: http://localhost:$API_PORT"
  else
    info "Core API: http://localhost:$API_PORT"
  fi
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
      --mode) [ "$#" -ge 2 ] || die "--mode requires a value"; INSTALL_MODE="$2"; shift ;;
      --non-interactive) NON_INTERACTIVE=true ;;
      --skip-docker-check) SKIP_DOCKER_CHECK=true ;;
      --help|-h) usage; exit 0 ;;
      *) die "Unknown argument: $1" ;;
    esac
    shift
  done
  [ "$command_name" = install ] || RECONFIGURE=true
  select_install_mode
  ensure_docker
  select_configuration
  install_node
}

if [ "${GOLDENERA_INSTALLER_LIBRARY_ONLY:-false}" != true ]; then
  main "$@"
fi
