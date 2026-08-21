#!/bin/bash
set -e

# ==============================================================================
# CONFIG
# ==============================================================================
WRAPPER_SRC="/usr/src/goldenera-randomx"
RANDOMX_SRC="${WRAPPER_SRC}/RandomX"

APP_HOME="/app"
OVERRIDES_DIR="${APP_HOME}/overrides"
NATIVE_PKG_DIR="${OVERRIDES_DIR}/native"
APP_JAR="${APP_HOME}/app.jar"
MEMORY_SIZING_LIB="${GOLDENERA_MEMORY_SIZING_LIB:-/usr/local/lib/goldenera/memory-sizing.sh}"
MAX_DIRECT_MEMORY_MB=512

DATA_DIR="${APP_HOME}/node_data"
LOG_DIR="${APP_HOME}/node_logs"
NATIVE_TMP_DIR="${APP_HOME}/native-tmp"

JAVA_BIN=$(which java)
if [ -z "$JAVA_BIN" ]; then
    JAVA_BIN="/opt/java/openjdk/bin/java"
fi

ARCH=$(uname -m)

echo ">>> [BOOT] GoldenEra Node Initialization"
echo ">>> [INFO] CPU: $ARCH"

# ==============================================================================
# PERMISSION FIX
# ==============================================================================
echo ">>> [INIT] Enforcing permissions for persistence layers..."
mkdir -p "$DATA_DIR" "$LOG_DIR" "$OVERRIDES_DIR" "$NATIVE_TMP_DIR"

chown -R blockchain:blockchain "$DATA_DIR"
chown -R blockchain:blockchain "$LOG_DIR"
chown blockchain:blockchain "$OVERRIDES_DIR" "$NATIVE_PKG_DIR"
chown blockchain:blockchain "$NATIVE_TMP_DIR"
chmod 700 "$DATA_DIR" "$NATIVE_TMP_DIR"

# ==============================================================================
# MEMORY CONFIGURATION
# 
# Automatic sizing accounts for the effective cgroup limit, host availability,
# huge pages, RandomX, RocksDB, PostgreSQL, direct buffers, JVM native memory,
# and operating-system headroom. JAVA_HEAP_MB remains an explicit override.
# ==============================================================================
if [ ! -r "$MEMORY_SIZING_LIB" ]; then
    echo ">>> [FATAL] Memory sizing library not found: $MEMORY_SIZING_LIB"
    exit 1
fi
# shellcheck source=memory-sizing.sh
. "$MEMORY_SIZING_LIB"

FULL_MEMORY_MINING=false
if ge_memory_is_true "${MINING_ENABLE:-false}"; then
    case "${MINING_MEMORY_MODE:-FULL}" in
        FULL|Full|full) FULL_MEMORY_MINING=true ;;
    esac
fi

LARGE_PAGES_USABLE=false
SETPRIV_CAPABILITY_ARGS=(
    --inh-caps=-all
    --ambient-caps=-all
    --bounding-set=-all
)
if "$FULL_MEMORY_MINING"; then
    if ge_process_has_capability_bit 14; then
        LARGE_PAGES_USABLE=true
        SETPRIV_CAPABILITY_ARGS=(
            --inh-caps=-all,+ipc_lock
            --ambient-caps=-all,+ipc_lock
            --bounding-set=-all,+ipc_lock
        )
        echo ">>> [INFO] RandomX large-page access enabled with IPC_LOCK."
    else
        echo ">>> [WARN] IPC_LOCK is unavailable; RandomX may use standard memory."
        echo ">>> [WARN] Automatic heap sizing will keep reserved huge pages and fallback memory in its budget."
    fi
fi

if [ -n "${JAVA_HEAP_MB:-}" ]; then
    case "$JAVA_HEAP_MB" in
        *[!0-9]*)
            echo ">>> [FATAL] JAVA_HEAP_MB must be a positive integer, got: $JAVA_HEAP_MB"
            exit 1
            ;;
    esac
    if [ "$JAVA_HEAP_MB" -lt 512 ]; then
        echo ">>> [FATAL] JAVA_HEAP_MB must be at least 512 MB."
        exit 1
    fi
    echo ">>> [INFO] Using explicit JAVA_HEAP_MB: ${JAVA_HEAP_MB} MB"
    JAVA_MEM_OPTS="-Xms${JAVA_HEAP_MB}m -Xmx${JAVA_HEAP_MB}m"
else
    if ! ge_detect_memory_environment; then
        echo ">>> [FATAL] Unable to detect host or container memory limits. Set JAVA_HEAP_MB explicitly."
        exit 1
    fi

    CGROUP_LIMIT_LABEL="unlimited"
    if [ "$GE_CGROUP_MEMORY_LIMIT_MB" -gt 0 ]; then
        CGROUP_LIMIT_LABEL="${GE_CGROUP_MEMORY_LIMIT_MB} MB"
    fi
    echo ">>> [INFO] Host Memory: ${GE_HOST_MEMORY_MB} MB; cgroup limit: ${CGROUP_LIMIT_LABEL}"
    echo ">>> [INFO] Effective Memory: ${GE_EFFECTIVE_MEMORY_MB} MB; currently available: ${GE_MEMORY_AVAILABLE_MB} MB"
    echo ">>> [INFO] Huge Pages: ${GE_HUGEPAGES_TOTAL_MB} MB reserved, ${GE_HUGEPAGES_FREE_MB} MB free"

    if ! ge_calculate_auto_heap_mb \
        "$GE_EFFECTIVE_MEMORY_MB" \
        "$GE_MEMORY_AVAILABLE_MB" \
        "$GE_HUGEPAGES_TOTAL_MB" \
        "$GE_HUGEPAGES_FREE_MB" \
        "${MINING_ENABLE:-false}" \
        "${MINING_MEMORY_MODE:-FULL}" \
        "${ROCKSDB_BLOCK_CACHE_MB:-512}" \
        "${ROCKSDB_WRITE_BUFFER_MB:-64}" \
        "${ROCKSDB_MAX_WRITE_BUFFERS:-4}" \
        "${POSTGRESQL_ENABLE:-${EXPLORER_ENABLE:-true}}" \
        "$MAX_DIRECT_MEMORY_MB" \
        "$LARGE_PAGES_USABLE"; then
        echo ">>> [FATAL] Safe automatic Java heap sizing failed: ${GE_MEMORY_ERROR:-invalid memory configuration}"
        echo ">>> [FATAL] Reserve breakdown: RandomX+huge-pages=${GE_RANDOMX_HUGEPAGE_RESERVE_MB:-unknown} MB, runtime=${GE_RUNTIME_RESERVE_MB:-unknown} MB"
        echo ">>> [FATAL] Add memory, reduce native caches, disable FULL mining/Explorer, or set JAVA_HEAP_MB explicitly."
        exit 1
    fi

    echo ">>> [INFO] Reserve: RandomX rollover peak=${GE_RANDOMX_PEAK_RESERVE_MB} MB, huge-page coverage=${GE_RANDOMX_HUGEPAGE_COVERAGE_MB} MB, total RandomX+huge-pages=${GE_RANDOMX_HUGEPAGE_RESERVE_MB} MB"
    echo ">>> [INFO] Reserve: RocksDB cache=${GE_ROCKSDB_CACHE_RESERVE_MB} MB, RocksDB writes=${GE_ROCKSDB_WRITE_RESERVE_MB} MB"
    echo ">>> [INFO] Reserve: direct=${GE_DIRECT_MEMORY_RESERVE_MB} MB, PostgreSQL=${GE_POSTGRESQL_RESERVE_MB} MB, OS+JVM=${GE_SYSTEM_JVM_RESERVE_MB} MB"
    echo ">>> [INFO] Heap limits: total-budget=${GE_HEAP_FROM_TOTAL_MB} MB, available-budget=${GE_HEAP_FROM_AVAILABLE_MB} MB, ${GE_HEAP_PERCENT_CAP}% cap=${GE_HEAP_PERCENT_CAP_MB} MB"
    echo ">>> [INFO] Auto-calculated Java Heap: ${GE_AUTO_HEAP_MB} MB"
    JAVA_MEM_OPTS="-Xms${GE_AUTO_HEAP_MB}m -Xmx${GE_AUTO_HEAP_MB}m"
fi

# ==============================================================================
# RANDOMX JIT COMPILATION
# ==============================================================================
if [ "$ARCH" = "x86_64" ]; then
    TARGET_FILENAME="librandomx_linux_x86_64.so"
elif [ "$ARCH" = "aarch64" ]; then
    TARGET_FILENAME="librandomx_linux_aarch64.so"
else
    echo ">>> [FATAL] Unsupported architecture: $ARCH"
    exit 1
fi

mkdir -p "$NATIVE_PKG_DIR"
FINAL_LIB_PATH="${NATIVE_PKG_DIR}/${TARGET_FILENAME}"

if [ -f "$FINAL_LIB_PATH" ]; then
    echo ">>> [SKIP] Native library found. Skipping build."
else
    echo ">>> [BUILD] Compiling RandomX optimized for THIS CPU..."
    
    if [ ! -d "$RANDOMX_SRC" ]; then
        echo ">>> [FATAL] Source code not found at $RANDOMX_SRC"
        exit 1
    fi

    mkdir -p "$RANDOMX_SRC/build"
    cd "$RANDOMX_SRC/build"
    rm -rf *

    cmake .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DARCH=native \
        -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_C_FLAGS="-fPIC" \
        -DCMAKE_SHARED_LINKER_FLAGS="-z noexecstack" > /dev/null
    
    make -j$(nproc) > /dev/null

    if [ -f "librandomx.so" ]; then
        cp librandomx.so "$FINAL_LIB_PATH"
        chown blockchain:blockchain "$FINAL_LIB_PATH"
        echo ">>> [SUCCESS] Library compiled."
    else
        echo ">>> [FATAL] Build failed."
        exit 1
    fi
    rm -rf "$RANDOMX_SRC/build"
    cd "$APP_HOME"
fi

# ==============================================================================
# LAUNCH APP
# ==============================================================================
echo ">>> [BOOT] Launching Spring Boot..."

# NOTE: Native memory (RandomX ~2.5GB, RocksDB cache) is NOT controlled by JVM!
# MaxDirectMemorySize only limits Java DirectByteBuffer (Netty, NIO buffers).
# 512MB is sufficient for Netty P2P + HTTP server buffers.

exec setpriv --reuid=blockchain --regid=blockchain --init-groups \
  "${SETPRIV_CAPABILITY_ARGS[@]}" \
  "$JAVA_BIN" \
  -server \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  ${JAVA_MEM_OPTS} \
  -XX:MaxDirectMemorySize=${MAX_DIRECT_MEMORY_MB}m \
  -XX:+AlwaysPreTouch \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseStringDeduplication \
  -DAPP_DATA_DIR=$DATA_DIR \
  -Djava.security.egd=file:/dev/./urandom \
  -cp ${OVERRIDES_DIR}:${APP_JAR} \
  org.springframework.boot.loader.launch.JarLauncher
