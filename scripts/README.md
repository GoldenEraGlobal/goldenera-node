# Scripts Directory

This directory contains build and deployment scripts for the GoldenEra Node.

## Files

- **`entrypoint.sh`** - Docker container entrypoint script
  - Handles permissions setup
  - Calculates a cgroup-, huge-page-, and service-aware Java heap size
  - Preserves only `IPC_LOCK` for RandomX huge pages and budgets for fallback memory
  - Compiles RandomX library optimized for CPU architecture
  - Launches Spring Boot application with ZGC garbage collector

- **`build-image.sh`** - Local Docker image build script
  - Loads GitHub credentials from `.github_creds`
  - Builds Docker image with secure token handling
  - Tags image as `goldenera-node:local`

- **`install.sh`** - Interactive Ubuntu, Debian, and macOS installer
  - Installs Docker when needed
  - Offers an automatic miner profile or a polished manual configuration flow
  - Generates a secure Compose deployment and management command
  - Supports public-image updates and local sandbox images

- **`install.ps1`** - Equivalent Windows PowerShell installer
  - Uses the same automatic/manual profiles, arrow-key menus, and `(y/N)` prompts

- **`tests/install-smoke.sh`** and **`tests/install-smoke.ps1`**
  - Validate generated configuration without changing the host

- **`tests/install-e2e.sh`**
  - Starts and probes the real local sandbox image

- **`tests/memory-sizing-test.sh`**
  - Validates automatic heap sizing and RandomX huge-page targets

## Usage

### Building Local Docker Image

```bash
./scripts/build-image.sh
```

Make sure you have `.github_creds` file in the project root with:
```bash
GITHUB_USER=your_username
GITHUB_TOKEN=your_token
```

### Running with Docker Compose

After building:
```bash
docker compose -f docker-compose.local.yml up -d
```

### Testing the automated installer

```bash
./scripts/build-sandbox-image.sh
./scripts/tests/memory-sizing-test.sh
./scripts/tests/install-smoke.sh
./scripts/tests/install-e2e.sh
```
