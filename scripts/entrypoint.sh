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
RANDOMX_LARGE_PAGE_WORKING_SET_MB=2560
JAVA_NMT_LEVEL="${JAVA_NMT_LEVEL:-summary}"
JAVA_JFR_ENABLE="${JAVA_JFR_ENABLE:-true}"

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

MEMORY_ENV_DETECTED=false
if ge_detect_memory_environment; then
    MEMORY_ENV_DETECTED=true
fi

HUGEPAGES_OUTSIDE_MEMORY_CGROUP=false
if [ "${GE_CGROUP_MEMORY_LIMIT_MB:-0}" -gt 0 ]; then
    HUGEPAGES_OUTSIDE_MEMORY_CGROUP=true
fi

LARGE_PAGES_USABLE=false
SETPRIV_CAPABILITY_ARGS=(
    --inh-caps=-all
    --ambient-caps=-all
    --bounding-set=-all
)
if "$FULL_MEMORY_MINING"; then
    if ! ge_process_has_capability_bit 14; then
        echo ">>> [WARN] IPC_LOCK is unavailable; RandomX will use standard memory."
    elif ! "$MEMORY_ENV_DETECTED"; then
        echo ">>> [WARN] Unable to inspect huge pages; RandomX will use standard memory."
    elif [ "$GE_HUGEPAGES_FREE_MB" -lt "$RANDOMX_LARGE_PAGE_WORKING_SET_MB" ]; then
        echo ">>> [WARN] RandomX large pages require at least ${RANDOMX_LARGE_PAGE_WORKING_SET_MB} MB free; found ${GE_HUGEPAGES_FREE_MB} MB."
        echo ">>> [WARN] RandomX will use standard memory without a failed large-page allocation attempt."
    else
        LARGE_PAGES_USABLE=true
        SETPRIV_CAPABILITY_ARGS=(
            --inh-caps=-all,+ipc_lock
            --ambient-caps=-all,+ipc_lock
            --bounding-set=-all,+ipc_lock
        )
        echo ">>> [INFO] RandomX large-page access enabled with IPC_LOCK and ${GE_HUGEPAGES_FREE_MB} MB free."
    fi
fi

calculate_safe_heap_budget() {
    ge_calculate_auto_heap_mb \
        "$GE_EFFECTIVE_MEMORY_MB" \
        "$1" \
        "$GE_HUGEPAGES_TOTAL_MB" \
        "$GE_HUGEPAGES_FREE_MB" \
        "${MINING_ENABLE:-false}" \
        "${MINING_MEMORY_MODE:-FULL}" \
        "${ROCKSDB_BLOCK_CACHE_MB:-512}" \
        "${ROCKSDB_WRITE_BUFFER_MB:-32}" \
        "${ROCKSDB_MAX_WRITE_BUFFERS:-2}" \
        "${POSTGRESQL_ENABLE:-${EXPLORER_ENABLE:-true}}" \
        "$MAX_DIRECT_MEMORY_MB" \
        "$LARGE_PAGES_USABLE" \
        "$HUGEPAGES_OUTSIDE_MEMORY_CGROUP"
}

if [ -n "${JAVA_HEAP_MB:-}" ]; then
    case "$JAVA_HEAP_MB" in
        *[!0-9]*)
            echo ">>> [FATAL] JAVA_HEAP_MB must be a positive integer, got: $JAVA_HEAP_MB"
            exit 1
            ;;
    esac
    if [ "$JAVA_HEAP_MB" -lt 1024 ]; then
        echo ">>> [FATAL] JAVA_HEAP_MB must be at least 1024 MB."
        exit 1
    fi
    if ! "$MEMORY_ENV_DETECTED"; then
        echo ">>> [FATAL] Explicit JAVA_HEAP_MB cannot be safety-checked because memory limits are unavailable."
        exit 1
    fi
    if ! calculate_safe_heap_budget "$GE_EFFECTIVE_MEMORY_MB"; then
        echo ">>> [FATAL] The configured memory budget cannot safely run this service profile: $GE_MEMORY_ERROR"
        exit 1
    fi
    if [ "$JAVA_HEAP_MB" -gt "$GE_AUTO_HEAP_MB" ]; then
        echo ">>> [FATAL] JAVA_HEAP_MB=${JAVA_HEAP_MB} exceeds the safe maximum ${GE_AUTO_HEAP_MB} MB for this budget."
        exit 1
    fi
    echo ">>> [INFO] Using explicit JAVA_HEAP_MB: ${JAVA_HEAP_MB} MB"
    RESOLVED_HEAP_MB="$JAVA_HEAP_MB"
else
    if ! "$MEMORY_ENV_DETECTED"; then
        echo ">>> [FATAL] Unable to detect host or container memory limits. Configure a hard container memory limit."
        exit 1
    fi

    CGROUP_LIMIT_LABEL="unlimited"
    if [ "$GE_CGROUP_MEMORY_LIMIT_MB" -gt 0 ]; then
        CGROUP_LIMIT_LABEL="${GE_CGROUP_MEMORY_LIMIT_MB} MB"
    fi
    echo ">>> [INFO] Host Memory: ${GE_HOST_MEMORY_MB} MB; cgroup limit: ${CGROUP_LIMIT_LABEL}"
    echo ">>> [INFO] Effective Memory: ${GE_EFFECTIVE_MEMORY_MB} MB; currently available: ${GE_MEMORY_AVAILABLE_MB} MB; cgroup usage: ${GE_CGROUP_MEMORY_USAGE_MB:-0} MB"
    echo ">>> [INFO] Huge Pages: ${GE_HUGEPAGES_TOTAL_MB} MB reserved, ${GE_HUGEPAGES_FREE_MB} MB free"

    if ! calculate_safe_heap_budget "$GE_MEMORY_AVAILABLE_MB"; then
        echo ">>> [FATAL] Safe automatic Java heap sizing failed: ${GE_MEMORY_ERROR:-invalid memory configuration}"
        echo ">>> [FATAL] Reserve breakdown: RandomX+huge-pages=${GE_RANDOMX_HUGEPAGE_RESERVE_MB:-unknown} MB, runtime=${GE_RUNTIME_RESERVE_MB:-unknown} MB"
        echo ">>> [FATAL] Increase the container budget, reduce native caches, or disable FULL mining/Explorer."
        exit 1
    fi

    echo ">>> [INFO] Reserve: RandomX rollover peak=${GE_RANDOMX_PEAK_RESERVE_MB} MB, huge-page coverage=${GE_RANDOMX_HUGEPAGE_COVERAGE_MB} MB, memory-budget RandomX=${GE_RANDOMX_HUGEPAGE_RESERVE_MB} MB, host huge-pages=${GE_HUGEPAGE_HOST_RESERVE_MB} MB"
    echo ">>> [INFO] Reserve: RocksDB cache=${GE_ROCKSDB_CACHE_RESERVE_MB} MB, RocksDB writes=${GE_ROCKSDB_WRITE_RESERVE_MB} MB"
    echo ">>> [INFO] Reserve: direct=${GE_DIRECT_MEMORY_RESERVE_MB} MB, PostgreSQL=${GE_POSTGRESQL_RESERVE_MB} MB, OS+JVM=${GE_SYSTEM_JVM_RESERVE_MB} MB"
    echo ">>> [INFO] Heap limits: total-budget=${GE_HEAP_FROM_TOTAL_MB} MB, available-budget=${GE_HEAP_FROM_AVAILABLE_MB} MB, ${GE_HEAP_PERCENT_CAP}% cap=${GE_HEAP_PERCENT_CAP_MB} MB"
    echo ">>> [INFO] Auto-calculated Java Heap: ${GE_AUTO_HEAP_MB} MB"
    RESOLVED_HEAP_MB="$GE_AUTO_HEAP_MB"
fi

JAVA_INITIAL_HEAP_MB="${JAVA_INITIAL_HEAP_MB:-1024}"
case "$JAVA_INITIAL_HEAP_MB" in
    ''|*[!0-9]*)
        echo ">>> [FATAL] JAVA_INITIAL_HEAP_MB must be a positive integer."
        exit 1
        ;;
esac
if [ "$JAVA_INITIAL_HEAP_MB" -lt 512 ] || [ "$JAVA_INITIAL_HEAP_MB" -gt "$RESOLVED_HEAP_MB" ]; then
    echo ">>> [FATAL] JAVA_INITIAL_HEAP_MB must be between 512 and ${RESOLVED_HEAP_MB} MB."
    exit 1
fi
JAVA_MEM_OPTS="-Xms${JAVA_INITIAL_HEAP_MB}m -Xmx${RESOLVED_HEAP_MB}m"
echo ">>> [INFO] Java initial heap: ${JAVA_INITIAL_HEAP_MB} MB; maximum heap: ${RESOLVED_HEAP_MB} MB"

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
    
    BUILD_JOBS="$(nproc)"
    [ "$BUILD_JOBS" -le 4 ] || BUILD_JOBS=4
    make -j"$BUILD_JOBS" > /dev/null

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

case "$JAVA_NMT_LEVEL" in
  off|summary|detail) ;;
  *)
    echo ">>> [FATAL] JAVA_NMT_LEVEL must be off, summary, or detail; got: $JAVA_NMT_LEVEL"
    exit 1
    ;;
esac

JAVA_DIAGNOSTIC_OPTS=(
  -XX:+HeapDumpOnOutOfMemoryError
  "-XX:HeapDumpPath=${LOG_DIR}"
)
if [ "$JAVA_NMT_LEVEL" != "off" ]; then
  JAVA_DIAGNOSTIC_OPTS+=("-XX:NativeMemoryTracking=${JAVA_NMT_LEVEL}")
fi
if ge_memory_is_true "$JAVA_JFR_ENABLE"; then
  JAVA_DIAGNOSTIC_OPTS+=(
    "-XX:StartFlightRecording=filename=${LOG_DIR}/goldenera.jfr,settings=default,dumponexit=true,maxage=6h,maxsize=256m"
  )
fi

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
  --enable-native-access=ALL-UNNAMED \
  "${JAVA_DIAGNOSTIC_OPTS[@]}" \
  -DAPP_DATA_DIR=$DATA_DIR \
  -Dgoldenera.randomx.large-pages-enabled=$LARGE_PAGES_USABLE \
  -Djava.security.egd=file:/dev/./urandom \
  -cp ${OVERRIDES_DIR}:${APP_JAR} \
  org.springframework.boot.loader.launch.JarLauncher
