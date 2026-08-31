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
package global.goldenera.node.core.blockchain.time;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.ClockMode;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

/** Pure deterministic sandbox-only chain clock. */
public final class DeterministicSandboxChainClock implements ChainClock {

	private final long genesisTimestampMs;
	private final long blockTimestampStepMs;
	private final long maxFutureSkewMs;

	public DeterministicSandboxChainClock(SandboxRuntimeContext runtimeContext) {
		Objects.requireNonNull(runtimeContext, "runtimeContext");
		if (!runtimeContext.isSandbox()) {
			throw new IllegalArgumentException("Deterministic sandbox clock requires SANDBOX execution domain");
		}
		SandboxManifest manifest = runtimeContext.manifestContext()
				.orElseThrow(() -> new IllegalArgumentException("Deterministic sandbox clock requires a manifest"))
				.manifest();
		if (manifest.clock().mode() != ClockMode.DETERMINISTIC || !manifest.features().deterministicClock()) {
			throw new IllegalArgumentException(
					"Deterministic sandbox clock requires matching clock mode and feature capability");
		}
		genesisTimestampMs = manifest.genesis().timestampMs();
		blockTimestampStepMs = manifest.clock().blockTimestampStepMs();
		maxFutureSkewMs = manifest.clock().maxFutureSkewMs();
	}

	@Override
	public Instant nextBlockTimestamp(BlockHeader parent) {
		Objects.requireNonNull(parent, "parent");
		long nextHeight = Math.addExact(parent.getHeight(), 1);
		return timestampWindow(nextHeight, parent.getTimestamp()).earliest();
	}

	@Override
	public BlockTimestampReservation reserveNextBlockTimestamp(
			BlockHeader parent,
			Optional<Instant> requestedTimestamp) {
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(requestedTimestamp, "requestedTimestamp");
		long nextHeight = Math.addExact(parent.getHeight(), 1);
		TimestampWindow window = timestampWindow(nextHeight, parent.getTimestamp());
		Instant timestamp = requestedTimestamp.orElse(window.earliest());
		validateWithinWindow(timestamp, window);
		return new BlockTimestampReservation(parent, timestamp);
	}

	@Override
	public void validateBlockTimestamp(BlockHeader child, BlockHeader parent, long ignoredProductionDriftMs) {
		Objects.requireNonNull(child, "child");
		Objects.requireNonNull(parent, "parent");
		checkArgument(child.getHeight() == Math.addExact(parent.getHeight(), 1),
				"Timestamp policy requires child height %s, got %s",
				parent.getHeight() + 1, child.getHeight());
		validateWithinWindow(child.getTimestamp(), timestampWindow(child.getHeight(), parent.getTimestamp()));
	}

	private TimestampWindow timestampWindow(long height, Instant parentTimestamp) {
		Instant nominal = nominalTimestamp(height);
		Instant afterParent = plusMillis(parentTimestamp, 1);
		Instant earliest = nominal.isAfter(afterParent) ? nominal : afterParent;
		Instant latest = plusMillis(nominal, maxFutureSkewMs);
		checkArgument(!earliest.isAfter(latest),
				"Parent timestamp leaves no valid deterministic timestamp at height %s", height);
		return new TimestampWindow(earliest, latest);
	}

	private Instant nominalTimestamp(long height) {
		checkArgument(height >= 0, "Block height cannot be negative");
		try {
			return Instant.ofEpochMilli(Math.addExact(genesisTimestampMs,
					Math.multiplyExact(height, blockTimestampStepMs)));
		} catch (ArithmeticException | DateTimeException e) {
			throw new IllegalArgumentException("Deterministic block timestamp exceeds supported range", e);
		}
	}

	private void validateWithinWindow(Instant timestamp, TimestampWindow window) {
		checkArgument(!timestamp.isBefore(window.earliest()),
				"Timestamp invalid: %s is before deterministic minimum %s", timestamp, window.earliest());
		checkArgument(!timestamp.isAfter(window.latest()),
				"Timestamp too far in future: %s (Max: %s)", timestamp, window.latest());
	}

	private Instant plusMillis(Instant instant, long millis) {
		try {
			return instant.plusMillis(millis);
		} catch (ArithmeticException | DateTimeException e) {
			throw new IllegalArgumentException("Deterministic block timestamp exceeds supported range", e);
		}
	}

	private record TimestampWindow(Instant earliest, Instant latest) {
	}
}
