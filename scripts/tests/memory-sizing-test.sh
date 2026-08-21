#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
# shellcheck source=../memory-sizing.sh
. "$ROOT_DIR/scripts/memory-sizing.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() {
  [ "$1" = "$2" ] || fail "expected '$2', got '$1'${3:+ ($3)}"
}

ge_calculate_auto_heap_mb 15990 11000 4000 4000 true FULL 512 64 4 true 512 true
assert_eq "$GE_AUTO_HEAP_MB" 3584 "16GB VM with an oversized legacy huge-page pool"

ge_calculate_auto_heap_mb 15990 12500 2560 2560 true FULL 512 64 4 true 512 true
assert_eq "$GE_AUTO_HEAP_MB" 5120 "16GB VM with right-sized huge pages"
assert_eq "$GE_RANDOMX_PEAK_RESERVE_MB" 6144 "FULL RandomX epoch-rollover reserve"

if ge_calculate_auto_heap_mb 8192 5000 2560 2560 true FULL 512 64 4 true 512 true; then
  fail "8GB full-memory mining configuration unexpectedly produced a heap"
fi

ge_calculate_auto_heap_mb 32768 28500 2560 2560 true FULL 512 64 4 true 512 true
assert_eq "$GE_AUTO_HEAP_MB" 16384 "32GB full-memory mining VM"

ge_calculate_auto_heap_mb 8192 8192 0 0 false FULL 512 64 4 true 512 false
assert_eq "$GE_AUTO_HEAP_MB" 3072 "non-mining container with an 8GB cgroup budget"

if ge_calculate_auto_heap_mb 4096 3000 2560 2560 true FULL 512 64 4 true 512 true; then
  fail "insufficient-memory configuration unexpectedly produced a heap"
fi

ge_calculate_auto_heap_mb 15990 12500 2560 2560 true FULL 512 64 4 true 512 false
assert_eq "$GE_AUTO_HEAP_MB" 2560 "large-page fallback remains inside a 16GB budget"
assert_eq "$GE_RANDOMX_HUGEPAGE_COVERAGE_MB" 0 "inaccessible huge pages do not cover RandomX memory"

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/goldenera-memory-test.XXXXXX")"
trap 'rm -rf "$fixture_dir"' EXIT
printf '%s\n' \
  'MemTotal:       33554432 kB' \
  'MemAvailable:   30000000 kB' \
  'HugePages_Total:    1280' \
  'HugePages_Free:     1200' \
  'Hugepagesize:       2048 kB' >"$fixture_dir/meminfo"
printf '%s\n' '8589934592' >"$fixture_dir/memory.max"

GOLDENERA_MEMINFO_PATH="$fixture_dir/meminfo" \
GOLDENERA_CGROUP_V2_MEMORY_MAX_PATH="$fixture_dir/memory.max" \
GOLDENERA_CGROUP_V1_MEMORY_LIMIT_PATH="$fixture_dir/missing-v1" \
  ge_detect_memory_environment

assert_eq "$GE_HOST_MEMORY_MB" 32768 "host memory detection"
assert_eq "$GE_CGROUP_MEMORY_LIMIT_MB" 8192 "cgroup v2 limit detection"
assert_eq "$GE_EFFECTIVE_MEMORY_MB" 8192 "effective memory limit"
assert_eq "$GE_MEMORY_AVAILABLE_MB" 8192 "available memory clamped to cgroup limit"
assert_eq "$GE_HUGEPAGES_TOTAL_MB" 2560 "huge-page reservation detection"
assert_eq "$GE_HUGEPAGES_FREE_MB" 2400 "free huge-page detection"

printf 'CapPrm:\t%s\n' '0000000000004000' >"$fixture_dir/status-with-ipc-lock"
GOLDENERA_PROCESS_STATUS_PATH="$fixture_dir/status-with-ipc-lock" \
  ge_process_has_capability_bit 14 || fail "IPC_LOCK capability was not detected"
printf 'CapPrm:\t%s\n' '0000000000000000' >"$fixture_dir/status-without-ipc-lock"
if GOLDENERA_PROCESS_STATUS_PATH="$fixture_dir/status-without-ipc-lock" \
    ge_process_has_capability_bit 14; then
  fail "missing IPC_LOCK capability was reported as available"
fi

printf '%s\n' '4294967296' >"$fixture_dir/memory.limit_in_bytes"
GOLDENERA_MEMINFO_PATH="$fixture_dir/meminfo" \
GOLDENERA_CGROUP_V2_MEMORY_MAX_PATH="$fixture_dir/missing-v2" \
GOLDENERA_CGROUP_V1_MEMORY_LIMIT_PATH="$fixture_dir/memory.limit_in_bytes" \
  ge_detect_memory_environment
assert_eq "$GE_CGROUP_MEMORY_LIMIT_MB" 4096 "cgroup v1 limit detection"
assert_eq "$GE_EFFECTIVE_MEMORY_MB" 4096 "effective cgroup v1 memory limit"

(
  GOLDENERA_INSTALLER_LIBRARY_ONLY=true
  # shellcheck source=../install.sh
  . "$ROOT_DIR/scripts/install.sh"
  assert_eq "$(resolve_mining_workers -1 16)" 14 "automatic mining worker count"
  assert_eq "$(resolve_mining_workers 8 16)" 8 "explicit mining worker count"
  assert_eq "$(calculate_randomx_hugepages 14)" 1280 "typical RandomX huge-page target"
  assert_eq "$(calculate_randomx_hugepages 64)" 1344 "large-worker RandomX huge-page target"
  assert_eq "$(read_linux_hugepage_count HugePages_Total "$fixture_dir/meminfo")" 1280 \
    "installer huge-page total verification"
  assert_eq "$(read_linux_hugepage_count HugePages_Free "$fixture_dir/meminfo")" 1200 \
    "installer huge-page free verification"
)

printf 'GoldenEra memory sizing tests passed.\n'
