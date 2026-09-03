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

### Reproducible sandbox image

From a clean committed worktree, with the project `mise` toolchain and Maven
dependencies installed locally:

```bash
./scripts/build-sandbox-image.sh
```

The default tag is `goldenera-node:sandbox-local`. OCI metadata records the node
commit, CryptoJ SHA-256, and Maven-pinned RandomX source commit. The build rejects
a dirty worktree or invalid release metadata. The published release image remains
the default Docker target.

### Testing memory sizing

```bash
./scripts/tests/memory-sizing-test.sh
```
