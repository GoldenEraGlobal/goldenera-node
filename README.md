# goldenera-node

The reference client for the Goldenera blockchain. It handles peer discovery,
consensus, transaction processing, block validation, and RandomX mining, with
an optional Explorer/indexer and webhook service.

## Requirements

- **CPU:** at least 4 vCPUs.
- **RAM:** at least 8 GB without mining; 16 GB for production `FULL` mining.
- **Storage:** SSD or NVMe.
- **Docker Engine and Compose**, or **Docker Desktop** on macOS/Windows.

See [memory profiles](#memory-and-mining) before enabling mining or the Explorer.
For an existing deployment with old memory settings, follow the
[upgrade guide](docs/upgrading.md) before updating the image.

## Installation

Install Docker and Compose first. In a new installation directory, download the
maintained [Compose file](docker-compose.yml) and [environment template](.env.example):

Ubuntu, Debian, or macOS:

```bash
mkdir -p ~/goldenera-node
cd ~/goldenera-node
curl -fsSL https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/docker-compose.yml -o docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/.env.example -o .env
chmod 600 .env
```

Windows PowerShell:

```powershell
$installDir = Join-Path $HOME "goldenera-node"
New-Item -ItemType Directory -Force $installDir | Out-Null
Set-Location $installDir
Invoke-WebRequest "https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/docker-compose.yml" -OutFile "docker-compose.yml"
Invoke-WebRequest "https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/.env.example" -OutFile ".env"
```

Do not overwrite an existing installation's configuration with these commands.
The template is intentionally not ready for an unattended first start. Edit
`.env` and complete this checklist before starting the node:

- Set `P2P_HOST` to the host's public IPv4 address. If the host is behind a
  router, forward TCP `P2P_PORT` (default `9000`) and allow it through the host
  firewall. A host behind CGNAT may not be reachable by inbound peers.
- Generate two different Base64 secrets for `SECURITY_HMAC_SECRET` and
  `SECURITY_AES_GCM_SECRET`. The node rejects empty or malformed secrets; do not
  reuse the same value for both settings.
- Replace `ADMIN_PASSWORD` and, when PostgreSQL is enabled,
  `POSTGRESQL_PASSWORD`.
- If mining is enabled, replace the zero `BENEFICIARY_ADDRESS` with the intended
  reward address and select a memory profile from [Memory and mining](#memory-and-mining).
- On Docker Desktop, allocate enough memory to the Docker VM for the node,
  PostgreSQL when enabled, and Docker overhead.

Then validate the Compose model and start the node:

```bash
docker compose config --quiet
docker compose up -d
docker compose logs -f node
```

`docker compose config` validates Compose syntax and variable expansion; it
does not validate application values inside `.env`. After startup,
`docker compose ps` should show `node` running and `db` healthy when PostgreSQL
is enabled. If `node` is restarting or stopped, inspect it with
`docker compose logs --tail=200 node`. The first image download and initial
blockchain synchronization can take time.

The checked-in Compose file includes PostgreSQL; the template enables the
Explorer and webhooks, with mining disabled. For a SQL-free deployment, use
the core-only settings below, remove the `db` service, and remove
`node.depends_on` from Compose.

## Configuration

Keep the full configuration in `.env`; [.env.example](.env.example) documents
the available deployment settings. Review these before the first start:

| Setting | Purpose |
| --- | --- |
| `NETWORK` | Blockchain network; defaults to `MAINNET` in the template. |
| `P2P_HOST` | Public IPv4 address used for peer discovery, not a domain name. |
| `MINING_ENABLE` | Enable or disable mining. |
| `BENEFICIARY_ADDRESS` | Your Goldenera reward address. Must not be the zero address when mining. |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | Replace the example admin credentials. |
| `POSTGRESQL_PASSWORD` | Replace the example database password when using PostgreSQL. |
| `SECURITY_HMAC_SECRET`, `SECURITY_AES_GCM_SECRET` | Two different Base64-encoded 32-byte secrets. |
| `NODE_MEMORY_LIMIT_MB` | Hard node-container memory limit; see the profiles below. |

For manual setup, run this command **twice** and assign one result to each
security secret:

```bash
openssl rand -base64 32
```

On Windows PowerShell, this equivalent command prints two independently
generated values:

```powershell
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
1..2 | ForEach-Object {
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    [Convert]::ToBase64String($bytes)
}
$rng.Dispose()
```

Keep secrets and the node identity private and preserve them across upgrades.
An imported mnemonic sets the persistent P2P identity; mining rewards go to
`BENEFICIARY_ADDRESS` and do not use that identity's private key.

### Optional services

| Deployment | `POSTGRESQL_ENABLE` | `EXPLORER_ENABLE` | `WEBHOOK_ENABLE` |
| --- | --- | --- | --- |
| Core only | `false` | `false` | `false` |
| Core with API keys and webhooks | `true` | `false` | `true` |
| Core with Explorer and webhooks | `true` | `true` | `true` |

Webhooks can be disabled independently. Explorer, webhooks, and protected core
API access all require PostgreSQL. Set `SECURITY_CORE_API_ENABLED=true` to
require API keys for core endpoints, except public health checks; leave it
`false` for a SQL-free node. Explorer API key protection is controlled by
`SECURITY_EXPLORER_API_ENABLED`.

### Memory and mining

Leave `JAVA_HEAP_MB` empty for automatic sizing. The container accounts for its
memory limit, current cgroup usage, huge pages, RocksDB, and enabled services.
A manual heap must fit the same native-memory budget.

| Host RAM | Mining | Huge pages | `NODE_MEMORY_LIMIT_MB` | Explorer/PostgreSQL |
| --- | --- | --- | ---: | --- |
| 8 GB | Off | Off | `8192` | Prefer off |
| 12 GB+ | Off | Off | `8192` | Supported |
| 16 GB+ | `FULL` | On | `9216` | Supported |
| 16 GB+ | `FULL` | Off | `12288` | Supported |

These are node-container limits, not total host budgets. PostgreSQL has a
separate limit (`POSTGRESQL_MEMORY_LIMIT_MB=1024` by default); leave room for
the OS and any host huge-page reservation.

On Linux, configure a worker-dependent pool of at least 1280 two-megabyte huge
pages (about 2.5 GB) before enabling `FULL` mining. Setting
`CONFIGURE_RANDOMX_HUGEPAGES` in `.env` does not configure the kernel. Compose
grants `IPC_LOCK` for huge-page access. See the
[upgrade guide](docs/upgrading.md#linux-huge-pages) for the required host setup.

`MINING_HASHING_THREADS=-1` selects the thread count automatically.
`MINING_MEMORY_MODE=FULL` is the production mode; `LIGHT` mining is restricted
to validated sandbox execution and at most four hashing threads.

### Advanced tuning

RocksDB, caches, HTTP threads, rate limits, and diagnostics are documented in
[.env.example](.env.example). Application-level defaults and additional options
are in [application.properties](src/main/resources/application.properties).

Two settings have behavior beyond performance tuning:

- `EQUIVOCATION_SINGLE_OBSERVATION_RETENTION_BLOCKS=0` keeps non-conflicting
  observations indefinitely. A finite window saves space but can miss conflicts
  arriving outside it.
- `API_KEY_AUTH_CACHE_TTL=5s` bounds stale authentication after direct database
  writes or changes from another node. Changes made through this process
  invalidate the cache after commit.

## Operations

Run these commands from the installation directory to update the image and
inspect the deployment:

```bash
docker compose pull
docker compose up -d --remove-orphans
docker compose ps
```

After editing `.env`, use `docker compose up -d` to apply changes; a container
restart alone does not reload environment variables.

Data lives in `node_data/` (blockchain and P2P identity) and `postgres_data/`
(when PostgreSQL is enabled); logs go to `node_logs/` by default.
Back up configuration and persistent data before upgrades. Never delete these
data directories or use `docker compose down -v` as an upgrade step.
See the [upgrade guide](docs/upgrading.md) for older installations.

## API and network access

Default ports are **9000** for P2P and **8080** for HTTP APIs. Allow inbound P2P
traffic for peer connectivity; restrict administrative HTTP access and use TLS
when exposing it remotely.

- [Swagger UI](http://localhost:8080/swagger-ui/index.html) documents the APIs.
- [Liveness](http://localhost:8080/api/core/v1/health/live) reports whether the
  process is running; it does not indicate that blockchain sync is complete.
- Admin endpoints use HTTP Basic authentication with the configured admin
  credentials.

Adjust URLs for your host and port. When changing `LISTEN_PORT` or `P2P_PORT`
in the checked-in Compose setup, also update the container-side port mappings.

### P2P chain identity

Protocol-v1 `STATUS` claims are unsigned and do not authenticate a peer.
Chain capabilities prevent accidental cross-chain peering; rejected, unbound
claims must not cause persistent bans for the claimed address.

Sandbox nodes should advertise and require `ge.chain.v1`. The legacy peer
allowlist is only for temporary compatibility with exact known old-node addresses
in isolated, disposable sandboxes: disable directory discovery, keep P2P off
public interfaces, and remove the allowlist after the bounded migration window.
It is not an authentication or authorization mechanism.

## Development

Local image builds and reproducible sandbox images are documented in
[scripts/README.md](scripts/README.md). Sandbox builds require a clean committed
worktree, the project [.mise.toml](.mise.toml) toolchain, and locally installed
Maven dependencies.

## License

[MIT](LICENSE).
