# Scripts Directory

This directory contains build and deployment scripts for the GoldenEra Node.

## Files

- **`entrypoint.sh`** - Docker container entrypoint script
  - Handles permissions setup
  - Calculates optimal Java heap size
  - Compiles RandomX library optimized for CPU architecture
  - Launches Spring Boot application with ZGC garbage collector

- **`build-image.sh`** - Local Docker image build script
  - Loads GitHub credentials from `.github_creds`
  - Builds Docker image with secure token handling
  - Tags image as `goldenera-node:local`

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
