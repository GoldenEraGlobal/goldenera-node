#!/usr/bin/env bash

# Memory sizing helpers shared by the production entrypoint and shell tests.
# This file intentionally does not change shell options because it is sourced.

ge_memory_is_positive_integer() {
  case "${1:-}" in
    ''|*[!0-9]*) return 1 ;;
    *) [ "$1" -gt 0 ] ;;
  esac
}

ge_memory_is_true() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
    *) return 1 ;;
  esac
}

ge_process_has_capability_bit() {
  local capability_bit="$1" status_path cap_hex cap_value
  status_path="${GOLDENERA_PROCESS_STATUS_PATH:-/proc/self/status}"

  case "$capability_bit" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "$capability_bit" -lt 63 ] || return 1

  cap_hex="$(awk '$1 == "CapPrm:" { print $2; exit }' "$status_path" 2>/dev/null || true)"
  case "$cap_hex" in
    ''|*[!0-9a-fA-F]*) return 1 ;;
  esac

  cap_value=$((16#$cap_hex))
  [ $((cap_value & (1 << capability_bit))) -ne 0 ]
}

ge_memory_meminfo_kb() {
  local key="$1" path="$2" value
  value="$(awk -v key="${key}:" '$1 == key { print $2; exit }' "$path" 2>/dev/null || true)"
  ge_memory_is_positive_integer "$value" || return 1
  printf '%s' "$value"
}

ge_memory_cgroup_limit_mb() {
  local path value limit_mb
  for path in \
    "${GOLDENERA_CGROUP_V2_MEMORY_MAX_PATH:-/sys/fs/cgroup/memory.max}" \
    "${GOLDENERA_CGROUP_V1_MEMORY_LIMIT_PATH:-/sys/fs/cgroup/memory/memory.limit_in_bytes}"; do
    [ -r "$path" ] || continue
    value="$(tr -d '[:space:]' <"$path")"
    case "$value" in
      ''|max|*[!0-9]*) continue ;;
    esac

    # cgroup v1 represents an unlimited value with a number close to LONG_MAX.
    # Realistic container limits are far shorter, so ignore those sentinels
    # before using shell integer arithmetic.
    [ "${#value}" -lt 16 ] || continue
    limit_mb=$((value / 1024 / 1024))
    [ "$limit_mb" -gt 0 ] || continue
    printf '%s' "$limit_mb"
    return 0
  done
  return 1
}

ge_detect_memory_environment() {
  local meminfo_path mem_total_kb mem_available_kb hugepage_size_kb
  local hugepages_total hugepages_free cgroup_limit_mb

  meminfo_path="${GOLDENERA_MEMINFO_PATH:-/proc/meminfo}"
  mem_total_kb="$(ge_memory_meminfo_kb MemTotal "$meminfo_path")" || return 1
  mem_available_kb="$(ge_memory_meminfo_kb MemAvailable "$meminfo_path" || true)"
  hugepage_size_kb="$(ge_memory_meminfo_kb Hugepagesize "$meminfo_path" || true)"
  hugepages_total="$(awk '$1 == "HugePages_Total:" { print $2; exit }' "$meminfo_path" 2>/dev/null || true)"
  hugepages_free="$(awk '$1 == "HugePages_Free:" { print $2; exit }' "$meminfo_path" 2>/dev/null || true)"

  GE_HOST_MEMORY_MB=$((mem_total_kb / 1024))
  GE_MEMORY_AVAILABLE_MB=$((mem_available_kb / 1024))
  [ "$GE_MEMORY_AVAILABLE_MB" -gt 0 ] || GE_MEMORY_AVAILABLE_MB="$GE_HOST_MEMORY_MB"

  GE_CGROUP_MEMORY_LIMIT_MB=0
  cgroup_limit_mb="$(ge_memory_cgroup_limit_mb || true)"
  if ge_memory_is_positive_integer "$cgroup_limit_mb"; then
    GE_CGROUP_MEMORY_LIMIT_MB="$cgroup_limit_mb"
  fi

  GE_EFFECTIVE_MEMORY_MB="$GE_HOST_MEMORY_MB"
  if [ "$GE_CGROUP_MEMORY_LIMIT_MB" -gt 0 ] \
      && [ "$GE_CGROUP_MEMORY_LIMIT_MB" -lt "$GE_EFFECTIVE_MEMORY_MB" ]; then
    GE_EFFECTIVE_MEMORY_MB="$GE_CGROUP_MEMORY_LIMIT_MB"
  fi
  if [ "$GE_MEMORY_AVAILABLE_MB" -gt "$GE_EFFECTIVE_MEMORY_MB" ]; then
    GE_MEMORY_AVAILABLE_MB="$GE_EFFECTIVE_MEMORY_MB"
  fi

  ge_memory_is_positive_integer "$hugepage_size_kb" || hugepage_size_kb=0
  ge_memory_is_positive_integer "$hugepages_total" || hugepages_total=0
  ge_memory_is_positive_integer "$hugepages_free" || hugepages_free=0
  GE_HUGEPAGES_TOTAL_MB=$((hugepages_total * hugepage_size_kb / 1024))
  GE_HUGEPAGES_FREE_MB=$((hugepages_free * hugepage_size_kb / 1024))
}

ge_calculate_auto_heap_mb() {
  local total_mb="$1" available_mb="$2" hugepages_total_mb="$3" hugepages_free_mb="$4"
  local mining_enabled="$5" memory_mode="$6" rocks_cache_mb="$7" rocks_write_buffer_mb="$8"
  local rocks_max_write_buffers="$9" postgresql_enabled="${10}" direct_memory_mb="${11:-512}"
  local large_pages_usable="${12:-false}"
  local randomx_expected_mb hugepage_coverage_cap_mb hugepage_coverage_mb candidate_mb

  ge_memory_is_positive_integer "$total_mb" || return 1
  ge_memory_is_positive_integer "$available_mb" || available_mb="$total_mb"
  ge_memory_is_positive_integer "$rocks_cache_mb" || rocks_cache_mb=512
  ge_memory_is_positive_integer "$rocks_write_buffer_mb" || rocks_write_buffer_mb=64
  ge_memory_is_positive_integer "$rocks_max_write_buffers" || rocks_max_write_buffers=4
  ge_memory_is_positive_integer "$direct_memory_mb" || direct_memory_mb=512
  ge_memory_is_positive_integer "$hugepages_total_mb" || hugepages_total_mb=0
  ge_memory_is_positive_integer "$hugepages_free_mb" || hugepages_free_mb=0

  GE_FULL_MEMORY_MINING=false
  if ge_memory_is_true "$mining_enabled"; then
    case "$memory_mode" in
      FULL|Full|full) GE_FULL_MEMORY_MINING=true ;;
    esac
  fi

  if "$GE_FULL_MEMORY_MINING"; then
    # RandomX builds replacement epoch resources before retiring the previous
    # dataset and can retain four cache-only epochs during eviction. Budget the
    # rollover peak; the persistent huge-page pool covers one FULL working set.
    randomx_expected_mb=6144
    hugepage_coverage_cap_mb=2560
    GE_HEAP_PERCENT_CAP=50
    hugepage_coverage_mb=0
    if ge_memory_is_true "$large_pages_usable"; then
      hugepage_coverage_mb="$hugepages_free_mb"
      [ "$hugepage_coverage_mb" -le "$hugepage_coverage_cap_mb" ] \
        || hugepage_coverage_mb="$hugepage_coverage_cap_mb"
    fi
  else
    # Current and replacement caches can overlap with four cached historical
    # epochs. These allocations use standard memory.
    randomx_expected_mb=1536
    hugepage_coverage_cap_mb=0
    GE_HEAP_PERCENT_CAP=60
    hugepage_coverage_mb=0
  fi

  GE_RANDOMX_PEAK_RESERVE_MB="$randomx_expected_mb"
  GE_RANDOMX_HUGEPAGE_COVERAGE_MB="$hugepage_coverage_mb"
  GE_RANDOMX_UNCOVERED_MB=$((randomx_expected_mb - hugepage_coverage_mb))
  GE_RANDOMX_HUGEPAGE_RESERVE_MB=$((hugepages_total_mb + GE_RANDOMX_UNCOVERED_MB))
  GE_ROCKSDB_CACHE_RESERVE_MB="$rocks_cache_mb"
  GE_ROCKSDB_WRITE_RESERVE_MB=$((rocks_write_buffer_mb * rocks_max_write_buffers * 2))
  GE_DIRECT_MEMORY_RESERVE_MB="$direct_memory_mb"
  GE_POSTGRESQL_RESERVE_MB=0
  if ge_memory_is_true "$postgresql_enabled"; then
    GE_POSTGRESQL_RESERVE_MB=512
  fi
  GE_SYSTEM_JVM_RESERVE_MB=1536
  GE_RUNTIME_RESERVE_MB=$((GE_ROCKSDB_CACHE_RESERVE_MB
    + GE_ROCKSDB_WRITE_RESERVE_MB
    + GE_DIRECT_MEMORY_RESERVE_MB
    + GE_POSTGRESQL_RESERVE_MB
    + GE_SYSTEM_JVM_RESERVE_MB))
  GE_TOTAL_RESERVE_MB=$((GE_RANDOMX_HUGEPAGE_RESERVE_MB + GE_RUNTIME_RESERVE_MB))

  GE_HEAP_FROM_TOTAL_MB=$((total_mb - GE_TOTAL_RESERVE_MB))
  GE_HEAP_FROM_AVAILABLE_MB=$((available_mb - GE_RANDOMX_UNCOVERED_MB - GE_RUNTIME_RESERVE_MB))
  GE_HEAP_PERCENT_CAP_MB=$((total_mb * GE_HEAP_PERCENT_CAP / 100))

  candidate_mb="$GE_HEAP_FROM_TOTAL_MB"
  [ "$GE_HEAP_FROM_AVAILABLE_MB" -ge "$candidate_mb" ] || candidate_mb="$GE_HEAP_FROM_AVAILABLE_MB"
  [ "$GE_HEAP_PERCENT_CAP_MB" -ge "$candidate_mb" ] || candidate_mb="$GE_HEAP_PERCENT_CAP_MB"
  GE_AUTO_HEAP_MB=$((candidate_mb / 256 * 256))

  if [ "$GE_AUTO_HEAP_MB" -lt 1024 ]; then
    GE_MEMORY_ERROR="Only ${candidate_mb} MB remains for the Java heap; at least 1024 MB is required."
    return 1
  fi
}
