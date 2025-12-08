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

DATA_DIR="${APP_HOME}/node_data"
LOG_DIR="${APP_HOME}/node_logs"

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
mkdir -p "$DATA_DIR" "$LOG_DIR" "$OVERRIDES_DIR"

chown -R blockchain:blockchain "$DATA_DIR"
chown -R blockchain:blockchain "$LOG_DIR"
chown -R blockchain:blockchain "$OVERRIDES_DIR"
chmod 700 "$DATA_DIR"

# ==============================================================================
# MEMORY CONFIGURATION
# 
# Memory layout for this node (native memory components):
#   - RandomX dataset:        ~2.5 GB (fixed, required for mining)
#   - RocksDB block cache:    ROCKSDB_BLOCK_CACHE_MB (set in .env)
#   - OS + buffers:           ~1.5 GB
#   - Java heap:              JAVA_HEAP_MB (set in .env or auto-calculated)
#
# For 16GB VPS example:
#   JAVA_HEAP_MB=8192 + ROCKSDB_BLOCK_CACHE_MB=2048 + RandomX=2560 + OS=1500 ≈ 14GB
# ==============================================================================
if [ -n "$JAVA_HEAP_MB" ] && [ "$JAVA_HEAP_MB" -gt 0 ] 2>/dev/null; then
    # Explicit heap size from .env
    echo ">>> [INFO] Using explicit JAVA_HEAP_MB: ${JAVA_HEAP_MB} MB"
    JAVA_MEM_OPTS="-Xms${JAVA_HEAP_MB}m -Xmx${JAVA_HEAP_MB}m"
else
    # Auto-calculate based on available memory
    if [ -f /sys/fs/cgroup/memory.max ]; then
        MEM_LIMIT_BYTES=$(cat /sys/fs/cgroup/memory.max)
        if [ "$MEM_LIMIT_BYTES" = "max" ]; then
            MEM_TOTAL_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
            MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
        else
            MEM_TOTAL_MB=$((MEM_LIMIT_BYTES / 1024 / 1024))
        fi
    else
        MEM_TOTAL_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
        MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
    fi

    echo ">>> [INFO] Total Memory: ${MEM_TOTAL_MB} MB"

    # Reserve for: RandomX (2.5GB) + RocksDB (from ROCKSDB_BLOCK_CACHE_MB, default 512MB) + OS (1.5GB)
    ROCKSDB_CACHE_MB=${ROCKSDB_BLOCK_CACHE_MB:-512}
    RESERVED_MB=$((2560 + ROCKSDB_CACHE_MB + 1536))
    
    if [ "$MEM_TOTAL_MB" -lt 5500 ]; then
        echo ">>> [WARN] Low Memory. Using minimal Heap."
        JAVA_MEM_OPTS="-Xms512m -Xmx1024m"
    else
        HEAP_SIZE_MB=$((MEM_TOTAL_MB - RESERVED_MB))
        if [ "$HEAP_SIZE_MB" -lt 512 ]; then HEAP_SIZE_MB=512; fi
        echo ">>> [INFO] Auto-calculated Java Heap: ${HEAP_SIZE_MB} MB (reserved ${RESERVED_MB} MB for RandomX+RocksDB+OS)"
        JAVA_MEM_OPTS="-Xms${HEAP_SIZE_MB}m -Xmx${HEAP_SIZE_MB}m"
    fi
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

exec su -s /bin/bash blockchain -c "$JAVA_BIN \
  -server \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  ${JAVA_MEM_OPTS} \
  -XX:MaxDirectMemorySize=512m \
  -XX:+AlwaysPreTouch \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseStringDeduplication \
  -DAPP_DATA_DIR=$DATA_DIR \
  -Djava.security.egd=file:/dev/./urandom \
  -cp ${OVERRIDES_DIR}:${APP_JAR} \
  org.springframework.boot.loader.launch.JarLauncher"