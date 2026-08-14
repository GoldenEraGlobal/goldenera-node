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

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;

/**
 * Parent-bound, single-use timestamp selected for one block assembly attempt.
 */
public final class BlockTimestampReservation implements AutoCloseable {

	private final long parentHeight;
	private final Hash parentHash;
	private final Instant timestamp;
	private final AtomicReference<State> state = new AtomicReference<>(State.RESERVED);

	BlockTimestampReservation(BlockHeader parent, Instant timestamp) {
		Objects.requireNonNull(parent, "parent");
		this.parentHeight = parent.getHeight();
		this.parentHash = Objects.requireNonNull(parent.getHash(), "parent hash");
		this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
	}

	public Instant consume(BlockHeader parent) {
		Objects.requireNonNull(parent, "parent");
		if (parent.getHeight() != parentHeight || !parent.getHash().equals(parentHash)) {
			throw new IllegalArgumentException("Timestamp reservation does not match the block parent");
		}
		if (!state.compareAndSet(State.RESERVED, State.CONSUMED)) {
			throw new IllegalStateException("Timestamp reservation has already been consumed or closed");
		}
		return timestamp;
	}

	public boolean isConsumed() {
		return state.get() == State.CONSUMED;
	}

	@Override
	public void close() {
		state.set(State.CLOSED);
	}

	private enum State {
		RESERVED,
		CONSUMED,
		CLOSED
	}
}
