# 🏛️ goldenera-node

**goldenera-node** is the official reference implementation of the Goldenera blockchain client. It serves as the backbone of the network, handling peer-to-peer communication, consensus mechanisms, transaction processing, and block validation. Built for performance and scalability, this node allows you to participate in the network and mine Goldenera coins.

---

## ⚠️ Hardware Requirements

To run a node successfully, your system **must** meet the following minimum requirements. Failing to meet these may result in node instability, syncing issues, or mining failures.

* **CPU:** Minimum **4 vCPUs** (High single-core performance is recommended for mining).
* **RAM:** Minimum **8 GB**, recommended **16 GB** for optimal performance.
* **Storage:** Fast SSD/NVMe (HDD is strictly not recommended for blockchain databases).

> **Note on Memory:** The node uses multiple memory pools:
> - **RandomX Dataset:** ~2.5 GB (fixed, required for mining)
> - **Java Heap:** Configurable via `JAVA_HEAP_MB`
> - **RocksDB Cache:** Configurable via `ROCKSDB_BLOCK_CACHE_MB` (off-heap)
> - **PostgreSQL + OS:** ~1.5 GB
>
> See `.env.example` for sizing recommendations by VPS size.

---


## 🛠️ Prerequisites

The automated installer below installs **Docker** and the **Docker Compose**
plugin when they are missing. For a manual installation, install them first.

* **Install Docker Engine:** Follow the official instructions for your operating system (Ubuntu, Debian, CentOS, etc.) here: [Install Docker Engine](https://docs.docker.com/engine/install/)
* **Verify Installation:** Run the following commands to ensure Docker is running correctly:
    ```bash
    docker --version
    docker compose version
    ```

---

## 🚀 Installation & Setup

### Automated installer (recommended)

The installer detects the operating system, installs Docker when it is missing,
asks for the installation directory, network, public P2P address, ports, mining
settings, reward address, node identity, and the built-in Explorer/indexer. It
generates secrets and starts the node from Docker Compose.

Ubuntu, Debian, or macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/scripts/install.sh | bash
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/scripts/install.ps1 | iex
```

The macOS and Windows paths install Docker Desktop when necessary. Docker
Desktop is a third-party product with its own license terms and may require an
administrator prompt, WSL2 setup on Windows, or one restart.

The installer creates a management command inside the selected directory:

```bash
~/goldenera-node/goldenera status
~/goldenera-node/goldenera logs
~/goldenera-node/goldenera update
~/goldenera-node/goldenera restart
~/goldenera-node/goldenera stop
```

On Windows, use the equivalent PowerShell controller:

```powershell
& "$env:LOCALAPPDATA\GoldenEra\Node\goldenera.ps1" update
```

`update` pulls the newest configured public image and recreates the containers
without deleting blockchain or PostgreSQL data. A local image configured with
`--local-image` is never pulled.

The mnemonic option configures the node's persistent P2P identity. Mining does
not use that private key; mining rewards are sent to `BENEFICIARY_ADDRESS`.

### Installer test with the local node image

From a clean committed worktree with the project dependencies installed:

```bash
./scripts/build-sandbox-image.sh
./scripts/tests/install-smoke.sh
./scripts/tests/install-e2e.sh
```

The end-to-end test generates a temporary installation configured with
`goldenera-node:sandbox-local`, starts the real node, verifies its liveness API,
and removes the test containers and temporary data.

### Reproducible local sandbox image

The sandbox test platform builds an image from a clean committed worktree with
the project `mise` toolchain and locally installed Maven artifacts:

```bash
./scripts/build-sandbox-image.sh
```

The image is tagged `goldenera-node:sandbox-local` by default. Its OCI metadata
records the exact node commit, CryptoJ SHA-256, and the Maven-pinned RandomX
source commit. The build fails if the worktree is dirty or those inputs do not
match; the published release-image path remains the default Docker target.

### 1. Optimize Linux Kernel (Recommended)

For optimal mining performance (RandomX), huge pages must be enabled on the host machine.

1.  Open the sysctl configuration file:
    ```bash
    sudo nano /etc/sysctl.conf
    ```
2.  Add the following line to the end of the file:
    ```properties
    vm.nr_hugepages=2000
    ```
3.  Apply the changes immediately:
    ```bash
    sudo sysctl -w vm.nr_hugepages=2000
    ```

### 2. Project Setup

Create a directory for your node and create the necessary configuration files.

#### `docker-compose.yml`

Create a file named `docker-compose.yml` and paste the following content:

```yaml
services:
  # ==============================================================================
  # GOLDENERA NODE
  # ==============================================================================
  node:
    image: ghcr.io/goldeneraglobal/goldenera-node:latest
    container_name: goldenera_node
    restart: unless-stopped
    pull_policy: always

    env_file:
      - .env

    environment:
      - POSTGRESQL_HOST=db
      - LOGGING_FILE=${LOGGING_FILE:-node.log}

    ports:
      - "${LISTEN_PORT:-8080}:8080"
      - "${P2P_PORT:-9000}:9000"

    volumes:
      - ./node_data:/app/node_data
      - ${LOGGING_DIR:-./node_logs}:/app/node_logs

    depends_on:
      db:
        condition: service_healthy
    ulimits:
      memlock:
        soft: -1
        hard: -1
    # Memory is managed via .env: JAVA_HEAP_MB + ROCKSDB_BLOCK_CACHE_MB
    # Minimum recommended: 8GB RAM (see .env.example for sizing guide)

  # ==============================================================================
  # DATABASE
  # ==============================================================================
  db:
    image: postgres:18.1-alpine
    container_name: goldenera_db
    restart: unless-stopped

    env_file:
      - .env

    command: postgres -c shared_buffers=512MB -c max_connections=100

    environment:
      POSTGRES_DB: ${POSTGRESQL_DB_NAME:-node_db}
      POSTGRES_USER: ${POSTGRESQL_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${POSTGRESQL_PASSWORD:-password}

    volumes:
      - ./postgres_data:/var/lib/postgresql/data

    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRESQL_USERNAME:-postgres}"]
      interval: 5s
      timeout: 5s
      retries: 5
```

#### `.env` Configuration

Create a file named `.env`. You **must** configure the variables marked as required below.

```dotenv
# Spring profile
SPRING_PROFILES_ACTIVE="prod"

# Admin PORT & Explorer PORT
LISTEN_PORT=8080

# PostgreSQL-backed shared services (API keys and webhooks) are enabled by default.
# Set false only for a SQL-free node. Explorer, webhooks and protected core API then must also be disabled.
POSTGRESQL_ENABLE=true

# Explorer/indexer runtime. Requires POSTGRESQL_ENABLE=true.
EXPLORER_ENABLE=true

# Blockchain and explorer webhook runtime. Requires POSTGRESQL_ENABLE=true.
WEBHOOK_ENABLE=true

# Node
NODE_IDENTITY_FILE="./node_data/.node_identity"
BLOCKCHAIN_DB_PATH="./node_data/blockchain"
PEER_REPUTATION_DB_PATH="./node_data/peer-reputation"
# =============================================================================
# MEMORY CONFIGURATION
# =============================================================================
# 
# Memory layout for GoldenEra Node:
#   - Java Heap (JAVA_HEAP_MB):     JVM memory for application (configurable)
#   - RocksDB Block Cache:          Native memory for disk cache (configurable)
#   - RandomX Dataset:              ~2.5 GB (FIXED, required for mining)
#   - OS + postgre buffers:                 ~1.5 GB
#
# EXAMPLES by VPS size:
#   8GB VPS:  JAVA_HEAP_MB=2048,  ROCKSDB_BLOCK_CACHE_MB=1024
#   16GB VPS: JAVA_HEAP_MB=8192,  ROCKSDB_BLOCK_CACHE_MB=2048
#   32GB VPS: JAVA_HEAP_MB=16384, ROCKSDB_BLOCK_CACHE_MB=8192
#
# Leave empty for auto-calculation (recommended for beginners)
# =============================================================================
JAVA_HEAP_MB=

# =============================================================================
# RocksDB Tuning
# =============================================================================
# Block cache: main read cache, shared across all column families (OFF-HEAP memory!)
# See memory examples above for recommended values
ROCKSDB_BLOCK_CACHE_MB=512

# Write buffer (memtable) size per column family
# Larger = better write throughput, higher memory usage
ROCKSDB_WRITE_BUFFER_MB=64

# Max write buffers per CF (allows writes during flush)
ROCKSDB_MAX_WRITE_BUFFERS=4

# Background jobs for compaction/flush (set to CPU cores / 2)
ROCKSDB_MAX_BACKGROUND_JOBS=6

# SST block size (16KB good for point lookups, increase for scan-heavy workloads)
ROCKSDB_BLOCK_SIZE_KB=16

# Bloom filter bits per key (10 = ~1% false positive rate)
ROCKSDB_BLOOM_FILTER_BITS=10

# Direct I/O (recommended for Linux, reduces double-buffering)
ROCKSDB_DIRECT_READS=true
ROCKSDB_DIRECT_WRITES=true

# Rate limit for background I/O in MB/s (0 = unlimited)
# Set to ~50-100 on slower disks to prevent compaction from starving reads
ROCKSDB_RATE_LIMIT_MB_PER_SEC=0

# =============================================================================
# BlobDB for Large Values (StoredBlock up to 7MB)
# =============================================================================
# Enable BlobDB for CF_BLOCKS (separates large values from LSM tree)
ROCKSDB_BLOB_ENABLED=true

# Minimum value size to store in blob files (bytes)
# Values >= this go to blob files, smaller stay in SST
# 64KB is good threshold - most headers are smaller, full blocks are larger
ROCKSDB_BLOB_MIN_BYTES=65536

# Blob file size in MB (256MB recommended for GCloud PD SSD)
ROCKSDB_BLOB_FILE_SIZE_MB=256

# Enable blob garbage collection during compaction
ROCKSDB_BLOB_GC_ENABLED=true

# Blob GC age cutoff (0.0-1.0): GC when this fraction of blobs are garbage
ROCKSDB_BLOB_GC_AGE_CUTOFF=0.25

# =============================================================================
# Application Cache (Caffeine - in-heap caching)
# =============================================================================
# Block cache: full StoredBlock objects
CACHE_BLOCK_MB=256

# Trie node cache: WorldState MPT nodes (critical for state lookups)
CACHE_TRIE_NODE_MB=256

# Transaction cache
CACHE_TX_MB=128

# Header cache: partial blocks (headers only) - entry count
CACHE_HEADER_MAX_ENTRIES=50000

# Height-to-hash cache - entry count
CACHE_HEIGHT_MAX_ENTRIES=100000

# Cache expiration time in minutes
CACHE_EXPIRE_MINUTES=60

# Network
NETWORK="MAINNET"
BENEFICIARY_ADDRESS="0x0000000000000000000000000000000000000000"

# P2P (use the public IP address!)
P2P_HOST=
P2P_PORT=9000

# Directory
DIRECTORY_PING_INTERVAL_IN_MS=30000

# Mempool
MEMPOOL_MAX_SIZE=100000
MEMPOOL_EXPIRE_TX_IN_MINUTES=60
MEMPOOL_MIN_ACCEPTABLE_FEE_IN_WEI=10
MEMPOOL_MAX_NONCE_GAP_PER_SENDER=64

# Mining
MINING_ENABLE=false
MINING_HASHING_THREADS=-1
MINING_MEMORY_MODE=FULL

# PostgreSQL
POSTGRESQL_HOST="localhost"
POSTGRESQL_PORT=5432
POSTGRESQL_DB_NAME="node_db"
POSTGRESQL_USERNAME="postgres"
POSTGRESQL_PASSWORD="postgres"

# Security
# openssl rand -base64 32
SECURITY_HMAC_SECRET=""
SECURITY_AES_GCM_SECRET=""
# If true, core-api security will be enabled (all endpoints will require API key)
# Requires POSTGRESQL_ENABLE=true so API keys can be managed and authenticated.
SECURITY_CORE_API_ENABLED=false
SECURITY_EXPLORER_API_ENABLED=true

# Admin
ADMIN_USERNAME="admin"
ADMIN_PASSWORD="abc123"

# Logging
LOGGING_DIR="./node_logs"
LOGGING_FILE="goldenera.log"
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_GLOBAL_GOLDENERA=INFO

# Throttling (api & p2p rate limiting)

# GLOBAL SAFETY NET (Per IP) - Applied to EVERYTHING
# Allows 500 requests per second. Just to stop DDoS scripts.
THROTTLING_GLOBAL_CAPACITY=500
THROTTLING_GLOBAL_REFILL_TOKENS=500

# PUBLIC CORE (Per IP) - Unauthenticated access to Core
# Strict: 100 tokens capacity, refills 20 per second.
THROTTLING_PUBLIC_CORE_CAPACITY=100
THROTTLING_PUBLIC_CORE_REFILL_TOKENS=20

# API KEY: DEFAULT / MEGA LOOSE (Per Key) - For everything else
# Mega Loose: 5000 tokens capacity, refills 2000 per second.
THROTTLING_API_KEY_DEFAULT_CAPACITY=5000
THROTTLING_API_KEY_DEFAULT_REFILL_TOKENS=2000

# API KEY: EXPLORER (Per Key) - Heavy queries
# Reasonable: 500 tokens capacity, refills 100 per second.
THROTTLING_API_KEY_EXPLORER_CAPACITY=500
THROTTLING_API_KEY_EXPLORER_REFILL_TOKENS=100

# P2P (Per Peer) - Protection against flood
# High throughput: 20000 capacity, refills 10000 per second (supports ~5000 TPS + overhead)
THROTTLING_P2P_CAPACITY=20000
THROTTLING_P2P_REFILL_TOKENS=10000
```

### 3. Critical Configuration Guide

Before running the node, you must adjust specific `.env` variables to match your environment.

| Variable | Description & Requirement |
| :--- | :--- |
| **`JAVA_HEAP_MB`** | Java heap size in MB. Leave empty for auto-calculation. See memory examples in `.env`. |
| **`ROCKSDB_BLOCK_CACHE_MB`** | RocksDB read cache (off-heap). Default `512`. See memory examples above. |
| **`LISTEN_PORT`** | The port for the Explorer/Admin API (exposed via Docker). Default is `8080`. |
| **`BENEFICIARY_ADDRESS`** | **CRITICAL.** Set this to your **Goldenera Wallet Address**. This is where your mining rewards will be sent. |
| **`P2P_HOST`** | **Must be your Public IP Address.**<br>Do not use a domain name here. This is used for peer discovery. |
| **`MINING_HASHING_THREADS`** | Number of CPU cores dedicated to mining. <br>`-1` = Auto (Leaves ~3 cores free for system/node). <br>Ensure at least 1 core remains free for system tasks. |
| **`MINING_MEMORY_MODE`** | RandomX mining memory mode: `FULL` (production default) or cache-only `LIGHT` (sandbox execution only, capped at 4 hashing threads). |
| **`SECURITY_HMAC_SECRET`** | **MANDATORY.** Generate a secure key using the command below. |
| **`SECURITY_AES_GCM_SECRET`** | **MANDATORY.** Generate a secure key using the command below. |
| **`ADMIN_USERNAME`** | Change this immediately for security. |
| **`ADMIN_PASSWORD`** | Change this immediately for security. |

#### Generating Security Secrets
Run the following command in your terminal to generate the required base64 secrets for the configuration above:

```bash
openssl rand -base64 32
```
*Copy the output and paste it into `SECURITY_HMAC_SECRET` and `SECURITY_AES_GCM_SECRET`.*

---

## 🏃‍♂️ Running the Node

Once configured, start the node using Docker Compose:

```bash
docker compose up -d
```

Check the logs to ensure everything is running correctly:

```bash
docker compose logs -f node
```

---

## P2P chain identity security

The protocol-v1 `STATUS` message carries a claimed node address and chain capabilities, but it is unsigned and is not cryptographically bound to the connection. The chain capability prevents accidental cross-chain peering; it is not peer authentication. Rejected, unbound STATUS claims must never create a persistent reputation ban for the claimed address.

Patched nodes should always advertise and require the versioned `ge.chain.v1` capability in sandbox networks. The legacy sandbox peer allowlist is only a short-lived wire-compatibility mechanism, not authentication or an authorization boundary. Enable it only for an isolated, disposable Docker sandbox with directory discovery disabled, no publicly reachable P2P listener, and an explicitly bounded migration window for exact known old-node addresses. Remove the allowlist as soon as those peers have been upgraded; the capability-bearing baseline is the supported default.

---

## 📚 API Documentation

The node comes with a built-in Swagger UI for exploring the API and Administration endpoints. Once the node is running, access it at:

**[http://localhost:8080/swagger-ui/index.html#/](http://localhost:8080/swagger-ui/index.html#/)**

*(Replace `localhost:8080` with your server's IP or domain if accessing remotely).*

---

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
