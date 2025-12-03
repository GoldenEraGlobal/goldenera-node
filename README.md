# 🏛️ goldenera-node

**goldenera-node** is the official reference implementation of the Goldenera blockchain client. It serves as the backbone of the network, handling peer-to-peer communication, consensus mechanisms, transaction processing, and block validation. Built for performance and scalability, this node allows you to participate in the network and mine Goldenera coins.

---

## ⚠️ Hardware Requirements

To run a node successfully, your system **must** meet the following minimum requirements. Failing to meet these may result in node instability, syncing issues, or mining failures.

* **CPU:** Minimum **4 vCPUs** (High single-core performance is recommended for mining).
* **RAM:** Minimum **8 GB** (DDR4/DDR5 recommended).
* **Storage:** Fast SSD/NVMe (HDD is strictly not recommended for blockchain databases).

> **Note on Memory:** The Docker configuration explicitly reserves **5GB of RAM** for the Node container (approximately 2.5GB for the RandomX mining dataset + 2.5GB for the Spring Boot application). You must have additional RAM available for the host operating system and the PostgreSQL database.

---

## 🛠️ Prerequisites

Before proceeding with the installation, ensure that **Docker** and the **Docker Compose** plugin are installed on your system.

* **Install Docker Engine:** Follow the official instructions for your operating system (Ubuntu, Debian, CentOS, etc.) here: [Install Docker Engine](https://docs.docker.com/engine/install/)
* **Verify Installation:** Run the following commands to ensure Docker is running correctly:
    ```bash
    docker --version
    docker compose version
    ```

---

## 🚀 Installation & Setup

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
    deploy:
      resources:
        reservations:
          # IMPORTANT: Minimum 5GB reservation required.
          # ~2.5GB for RandomX + ~2.5GB for Node application.
          # Ensure your host has extra RAM for the Database.
          memory: 5G

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

# Admin URL & Explorer URL
LISTEN_URL="http://localhost"
LISTEN_PORT=8080

# SSL
SSL_ENABLED=false
SSL_KEY_STORE=
SSL_KEY_STORE_PASSWORD=
SSL_FORCE_REDIRECT_TO_HTTPS=false

# Node
NODE_IDENTITY_FILE="./node_data/.node_identity"
BLOCKCHAIN_DB_PATH="./node_data/blockchain"
PEER_REPUTATION_DB_PATH="./node_data/peer-reputation"

# Network
NETWORK="MAINNET"
BENEFICIARY_ADDRESS="0x0000000000000000000000000000000000000000"

# P2P
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
MINING_ENABLE=true
MINING_HASHING_THREADS=-1

# PostgreSQL
POSTGRESQL_HOST="localhost"
POSTGRESQL_PORT=5432
POSTGRESQL_DB_NAME="node_db"
POSTGRESQL_USERNAME="postgres"
POSTGRESQL_PASSWORD="postgres"

# Security
SECURITY_HMAC_SECRET=""
SECURITY_AES_GCM_SECRET=""
SECURITY_CORS_ALLOWED_ORIGINS="http://localhost"

# Admin
ADMIN_USERNAME="admin"
ADMIN_PASSWORD="abc123"

# Logging
LOGGING_DIR="./node_logs"
LOGGING_FILE="goldenera.log"
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_GLOBAL_GOLDENERA=INFO
```

### 3. Critical Configuration Guide

Before running the node, you must adjust specific `.env` variables to match your environment.

| Variable | Description & Requirement |
| :--- | :--- |
| **`LISTEN_URL`** | **Set this to your public domain.**<br>Do not use `localhost` if you are running a public node. Example: `https://my-goldenera-node.com`. This tells the app its public address. |
| **`LISTEN_PORT`** | The port for the Explorer/Admin API (exposed via Docker). Default is `8080`. |
| **`BENEFICIARY_ADDRESS`** | **CRITICAL.** Set this to your **Goldenera Wallet Address**. This is where your mining rewards will be sent. |
| **`P2P_HOST`** | **Must be your Public IP Address.**<br>Do not use a domain name here. This is used for peer discovery. |
| **`MINING_HASHING_THREADS`** | Number of CPU cores dedicated to mining. <br>`-1` = Auto (Leaves ~3 cores free for system/node). <br>Ensure at least 1 core remains free for system tasks. |
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

## 📚 API Documentation

The node comes with a built-in Swagger UI for exploring the API and Administration endpoints. Once the node is running, access it at:

**[http://localhost:8080/swagger-ui/index.html#/](http://localhost:8080/swagger-ui/index.html#/)**

*(Replace `localhost:8080` with your server's IP or domain if accessing remotely).*

---

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.