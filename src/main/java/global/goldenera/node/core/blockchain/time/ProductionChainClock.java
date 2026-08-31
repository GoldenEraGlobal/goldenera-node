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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import global.goldenera.cryptoj.common.BlockHeader;

/** Preserves the production node's wall-clock timestamp behavior. */
public final class ProductionChainClock implements ChainClock {

	private final Clock wallClock;

	public ProductionChainClock() {
		this(Clock.systemUTC());
	}

	ProductionChainClock(Clock wallClock) {
		this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
	}

	@Override
	public Instant nextBlockTimestamp(BlockHeader parent) {
		Objects.requireNonNull(parent, "parent");
		long now = Instant.now(wallClock).toEpochMilli();
		long timestamp = (now / 1_000) * 1_000;
		timestamp = Math.max(timestamp, Math.addExact(parent.getTimestamp().toEpochMilli(), 1));
		return Instant.ofEpochMilli(timestamp);
	}

	@Override
	public Instant earliestNextBlockTimestamp(BlockHeader parent) {
		Objects.requireNonNull(parent, "parent");
		Instant now = Instant.now(wallClock);
		Instant afterParent = parent.getTimestamp().plusMillis(1);
		return afterParent.isAfter(now) ? afterParent : now;
	}

	@Override
	public void validateBlockTimestamp(BlockHeader child, BlockHeader parent, long productionAllowedFutureDriftMs) {
		Objects.requireNonNull(child, "child");
		Objects.requireNonNull(parent, "parent");
		checkArgument(productionAllowedFutureDriftMs >= 0, "Allowed future drift cannot be negative");
		checkArgument(child.getTimestamp().isAfter(parent.getTimestamp()),
				"Timestamp invalid: Child %s <= Parent %s",
				child.getTimestamp(), parent.getTimestamp());

		Instant maxTime = Instant.now(wallClock).plusMillis(productionAllowedFutureDriftMs);
		checkArgument(!child.getTimestamp().isAfter(maxTime),
				"Timestamp too far in future: %s (Max: %s)",
				child.getTimestamp(), maxTime);
	}
}
