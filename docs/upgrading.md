# Upgrading older installations

[Back to README](../README.md)

Use this guide for installations with an oversized manual heap, missing
container memory limits, or the old 4 GB huge-page pool. For routine image
updates, see [Operations](../README.md#operations).

Back up `.env`, Compose configuration, and persistent data before upgrading.
Take a VM/disk snapshot if the data cannot be recreated. Preserve `node_data/`
and `postgres_data/`; never delete them or run `docker compose down -v`.
Do not rotate existing passwords, security secrets, or the P2P identity as part
of this configuration update.

Choose a [memory profile](../README.md#memory-and-mining) first.
Leave `JAVA_HEAP_MB` empty so the entrypoint sizes the heap within the container
and native-memory budget.

## Installer-managed deployments

On Linux/macOS, back up the generated files and run the current installer in
reconfiguration mode:

```bash
cd ~/goldenera-node # use your actual installation directory
cp .env ".env.backup.$(date +%Y%m%d-%H%M%S)"
cp compose.yaml "compose.yaml.backup.$(date +%Y%m%d-%H%M%S)"
./goldenera stop
curl -fsSL https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/scripts/install.sh -o /tmp/goldenera-install.sh
chmod +x /tmp/goldenera-install.sh
/tmp/goldenera-install.sh reconfigure --mode manual --install-dir "$PWD"
./goldenera status
```

Review the network, public P2P address, reward address, Explorer setting, huge
pages, and memory limit when prompted. Reconfiguration preserves existing
secrets and bind-mounted data, but regenerates Compose and `.env`; compare the
backups to retain any custom settings.

If the host has an old huge-page reservation, review
[Linux huge pages](#linux-huge-pages) before reconfiguring. Disabling mining does
not automatically release a previous host reservation.

## Legacy manual Docker Compose deployments

Download the current Compose file alongside the old configuration:

```bash
cd /path/to/your/goldenera-node
cp .env ".env.backup.$(date +%Y%m%d-%H%M%S)"
cp docker-compose.yml "docker-compose.yml.backup.$(date +%Y%m%d-%H%M%S)"
curl -fsSL https://raw.githubusercontent.com/GoldenEraGlobal/goldenera-node/main/docker-compose.yml -o docker-compose.yml.new
```

Keep existing credentials, network, beneficiary, P2P address, ports, and identity
paths. Merge any custom mounts or port mappings into the new Compose file.
Add or replace these `.env` values without leaving duplicate keys:

```dotenv
JAVA_HEAP_MB=
JAVA_INITIAL_HEAP_MB=1024
JAVA_NMT_LEVEL=summary
JAVA_JFR_ENABLE=true

# Non-mining profile; choose the appropriate limit before enabling mining.
NODE_MEMORY_LIMIT_MB=8192
POSTGRESQL_MEMORY_LIMIT_MB=1024

ROCKSDB_BLOCK_CACHE_MB=512
ROCKSDB_WRITE_BUFFER_MB=32
ROCKSDB_MAX_WRITE_BUFFERS=2

MINING_MEMORY_MODE=FULL
CONFIGURE_RANDOMX_HUGEPAGES=false
SYNC_RANDOMX_VERIFICATION_MODE=LIGHT
```

Preserve `MINING_ENABLE`; if it is `true`, select a mining memory limit from the
[profile table](../README.md#memory-and-mining) instead of the non-mining value
above.

For Explorer deployments, set `POSTGRESQL_ENABLE`, `EXPLORER_ENABLE`, and
`WEBHOOK_ENABLE` to `true`. For SQL-free nodes, set all three and
`SECURITY_CORE_API_ENABLED` to `false`, and remove the `db` service and
`node.depends_on` from the new Compose file.

Validate the configuration and stop the old containers before changing host
memory settings:

```bash
docker compose -f docker-compose.yml.new --env-file .env config --quiet
docker compose down # never add -v
```

### Linux huge pages

Skip this section if the host has no huge-page reservation and you do not want
one. Huge pages reserve host RAM globally; do not change reservations needed by
other applications.

Find existing settings:

```bash
grep -R '^[[:space:]]*vm.nr_hugepages' /etc/sysctl.conf /etc/sysctl.d 2>/dev/null || true
```

Remove or update obsolete Goldenera `vm.nr_hugepages` entries in those files so
a reboot cannot restore the old pool. Keep one persistent setting for this
host.

For `FULL` mining, the baseline is 1280 two-megabyte pages (about 2.5 GB).
The installer calculates a larger target when the mining worker count requires
it; prefer installer configuration for that case.

```bash
sudo sh -c 'printf "vm.nr_hugepages=1280\n" > /etc/sysctl.d/99-goldenera-node.conf'
sudo sysctl -p /etc/sysctl.d/99-goldenera-node.conf
grep -E 'HugePages_Total|HugePages_Free|Hugepagesize' /proc/meminfo
```

Verify the page size and allocation before using
`NODE_MEMORY_LIMIT_MB=9216` and `CONFIGURE_RANDOMX_HUGEPAGES=true`.
The latter records the installer preference; it does not configure the kernel
in a manual Compose deployment.

Without huge pages, use `CONFIGURE_RANDOMX_HUGEPAGES=false` and at least
`NODE_MEMORY_LIMIT_MB=12288` for `FULL` mining.

For a non-mining host that no longer needs the old reservation, remove its
persistent setting and release the pages:

```bash
sudo sysctl -w vm.nr_hugepages=0
```

### Start and verify

Activate the new Compose file:

```bash
mv docker-compose.yml.new docker-compose.yml
docker compose pull
docker compose up -d --remove-orphans
docker compose ps
docker compose logs -f node
```

At startup, check the host/cgroup memory, huge-page availability, reserve
breakdown, and `Auto-calculated Java Heap` in the logs. If the entrypoint rejects
the budget, reduce enabled services or use a larger host; do not restore an
oversized heap.

Keep the backups until the node is healthy. Configuration rollback alone may
not undo database changes from a newer image; retain the previous image and
data snapshot if a full rollback is needed.
