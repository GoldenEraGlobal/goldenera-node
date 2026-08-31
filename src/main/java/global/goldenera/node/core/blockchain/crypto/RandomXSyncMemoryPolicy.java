/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.node.core.blockchain.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import global.goldenera.node.core.properties.RandomXSyncVerificationMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;

/** Fail-closed cgroup-aware admission for the temporary sync-owned RandomX dataset. */
final class RandomXSyncMemoryPolicy {

	private static final long MIB = 1024L * 1024;
	private final RandomXVerificationProperties properties;
	private final MemoryProbe memoryProbe;

	RandomXSyncMemoryPolicy(RandomXVerificationProperties properties, MemoryProbe memoryProbe) {
		this.properties = properties;
		this.memoryProbe = memoryProbe;
	}

	Decision decide(
			long expectedHashes,
			boolean explorerEnabled,
			boolean miningEnabled,
			boolean matchingMiningDataset) {
		properties.validate();
		if (properties.getVerificationMode() == RandomXSyncVerificationMode.LIGHT) {
			return new Decision(Outcome.LIGHT, "forced LIGHT mode");
		}
		if (matchingMiningDataset) {
			return new Decision(Outcome.REUSE_EXISTING, "matching mining dataset adds no native allocation");
		}
		if (expectedHashes < properties.getMinimumExpectedHashes()) {
			return new Decision(Outcome.LIGHT, "dataset initialization is not amortizable");
		}

		MemorySnapshot memory = memoryProbe.snapshot();
		if (!memory.known()) {
			return new Decision(Outcome.LIGHT, "effective memory is unknown");
		}
		long requiredMemory = explorerEnabled || miningEnabled
				? properties.getAutoPreferredMemoryMib()
				: properties.getAutoMinimumMemoryMib();
		if (memory.effectiveMemoryMib() < requiredMemory) {
			return new Decision(Outcome.LIGHT, "effective memory is below " + requiredMemory + " MiB");
		}
		long nativeHeadroom = memory.effectiveMemoryMib() - memory.maxHeapMib();
		if (nativeHeadroom < properties.getMinimumNativeHeadroomMib()) {
			return new Decision(Outcome.LIGHT, "JVM heap leaves only " + nativeHeadroom + " MiB native headroom");
		}
		if (memory.availableMemoryMib() < properties.getMinimumAvailableMemoryMib()) {
			return new Decision(Outcome.LIGHT, "current available memory is below the native safety floor");
		}
		return new Decision(Outcome.BUILD_DATASET, "safe memory and amortization budget");
	}

	enum Outcome {
		LIGHT,
		REUSE_EXISTING,
		BUILD_DATASET
	}

	record Decision(Outcome outcome, String reason) {
	}

	@FunctionalInterface
	interface MemoryProbe {
		MemorySnapshot snapshot();
	}

	record MemorySnapshot(
			long effectiveMemoryMib,
			long availableMemoryMib,
			long maxHeapMib,
			boolean known) {

		static MemorySnapshot system() {
			return SystemMemoryProbe.INSTANCE.snapshot();
		}
	}

	static final class SystemMemoryProbe implements MemoryProbe {
		static final SystemMemoryProbe INSTANCE = new SystemMemoryProbe(
				Path.of("/proc/meminfo"),
				Path.of("/sys/fs/cgroup/memory.max"),
				Path.of("/sys/fs/cgroup/memory.current"),
				Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"),
				Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));

		private final Path meminfo;
		private final Path cgroupV2Limit;
		private final Path cgroupV2Current;
		private final Path cgroupV1Limit;
		private final Path cgroupV1Current;

		SystemMemoryProbe(
				Path meminfo,
				Path cgroupV2Limit,
				Path cgroupV2Current,
				Path cgroupV1Limit,
				Path cgroupV1Current) {
			this.meminfo = meminfo;
			this.cgroupV2Limit = cgroupV2Limit;
			this.cgroupV2Current = cgroupV2Current;
			this.cgroupV1Limit = cgroupV1Limit;
			this.cgroupV1Current = cgroupV1Current;
		}

		@Override
		public MemorySnapshot snapshot() {
			try {
				long hostTotal = meminfoMib("MemTotal").orElse(0L);
				long hostAvailable = meminfoMib("MemAvailable").orElse(0L);
				long maxHeap = Runtime.getRuntime().maxMemory() / MIB;
				CgroupSnapshot cgroup = cgroupSnapshot();
				if (!cgroup.valid()) {
					return new MemorySnapshot(0, 0, maxHeap, false);
				}
				long effective = cgroup.finite()
						? positiveMinimum(hostTotal, cgroup.limitMib()) : hostTotal;
				long available = cgroup.finite()
						? Math.min(hostAvailable, Math.max(0L, cgroup.limitMib() - cgroup.currentMib()))
						: hostAvailable;
				return new MemorySnapshot(effective, available, maxHeap,
						effective > 0 && available > 0 && maxHeap > 0);
			} catch (RuntimeException e) {
				return new MemorySnapshot(0, 0, 0, false);
			}
		}

		private CgroupSnapshot cgroupSnapshot() {
			Optional<String> v2Limit = rawValue(cgroupV2Limit);
			if (v2Limit.isPresent()) {
				if ("max".equals(v2Limit.orElseThrow())) {
					return CgroupSnapshot.unbounded();
				}
				return finiteCgroup(v2Limit.orElseThrow(), cgroupV2Current);
			}
			Optional<String> v1Limit = rawValue(cgroupV1Limit);
			return v1Limit.isPresent()
					? finiteCgroup(v1Limit.orElseThrow(), cgroupV1Current)
					: CgroupSnapshot.unbounded();
		}

		private CgroupSnapshot finiteCgroup(String limitValue, Path currentPath) {
			OptionalLong limit = byteValueMib(limitValue);
			Optional<String> currentValue = rawValue(currentPath);
			OptionalLong current = currentValue.isPresent()
					? byteValueMib(currentValue.orElseThrow()) : OptionalLong.empty();
			if (limit.isEmpty() || current.isEmpty()) {
				return CgroupSnapshot.invalid();
			}
			return new CgroupSnapshot(true, true, limit.orElseThrow(), current.orElseThrow());
		}

		private Optional<String> rawValue(Path path) {
			try {
				return Optional.of(Files.readString(path).trim());
			} catch (IOException e) {
				return Optional.empty();
			}
		}

		private OptionalLong byteValueMib(String value) {
			try {
				if (!value.matches("[0-9]{1,19}")) {
					return OptionalLong.empty();
				}
				long bytes = Long.parseLong(value);
				if (bytes <= 0 || bytes >= Long.MAX_VALUE / 2) {
					return OptionalLong.empty();
				}
				return OptionalLong.of(bytes / MIB);
			} catch (NumberFormatException e) {
				return OptionalLong.empty();
			}
		}

		private OptionalLong meminfoMib(String key) {
			try {
				String prefix = key + ":";
				return Files.readAllLines(meminfo).stream()
						.map(String::trim)
						.filter(line -> line.startsWith(prefix))
						.map(line -> line.substring(prefix.length()).trim().split("\\s+")[0])
						.mapToLong(Long::parseLong)
						.map(kib -> kib / 1024L)
						.findFirst();
			} catch (IOException | NumberFormatException e) {
				return OptionalLong.empty();
			}
		}

		private long positiveMinimum(long first, long second) {
			if (first <= 0) {
				return Math.max(0L, second);
			}
			if (second <= 0) {
				return first;
			}
			return Math.min(first, second);
		}

		private record CgroupSnapshot(boolean finite, boolean valid, long limitMib, long currentMib) {
			static CgroupSnapshot unbounded() {
				return new CgroupSnapshot(false, true, 0, 0);
			}

			static CgroupSnapshot invalid() {
				return new CgroupSnapshot(true, false, 0, 0);
			}
		}
	}
}
