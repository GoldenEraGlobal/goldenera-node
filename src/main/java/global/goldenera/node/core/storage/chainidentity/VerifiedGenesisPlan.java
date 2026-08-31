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
package global.goldenera.node.core.storage.chainidentity;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.validation.ImmutableBlockSnapshot;
import global.goldenera.node.core.state.WorldState;

/** Opaque authorization to persist one locally verified genesis block and state. */
public final class VerifiedGenesisPlan {

	private final Block block;
	private final WorldState worldState;
	private final Address receivedFrom;
	private final Instant receivedAt;
	private final AtomicBoolean claimed = new AtomicBoolean();

	VerifiedGenesisPlan(Block block, WorldState worldState, Address receivedFrom, String expectedGenesisHash) {
		if (block == null || worldState == null || receivedFrom == null || expectedGenesisHash == null) {
			throw new IllegalArgumentException("Verified genesis plan fields cannot be null");
		}
		this.block = ImmutableBlockSnapshot.copyOf(block);
		this.worldState = worldState;
		this.receivedFrom = receivedFrom;
		this.receivedAt = this.block.getHeader().getTimestamp();
		if (this.block.getHeight() != 0L || !this.block.getTxs().isEmpty()) {
			throw new IllegalArgumentException("Verified genesis must be an empty height-zero block");
		}
		if (!this.block.getHash().toHexString().equals(expectedGenesisHash)) {
			throw new IllegalArgumentException("Verified genesis block does not match the expected chain identity");
		}
		Hash calculatedStateRoot = worldState.calculateRootHash();
		if (!calculatedStateRoot.equals(this.block.getHeader().getStateRootHash())) {
			throw new IllegalArgumentException("Verified genesis world state does not match the block state root");
		}
	}

	public String genesisHash() {
		return block.getHash().toHexString();
	}

	public ClaimedGenesis claimForPersistence() {
		if (!claimed.compareAndSet(false, true)) {
			throw new IllegalStateException("Verified genesis plan was already consumed");
		}
		return new ClaimedGenesis(block, worldState, receivedFrom, receivedAt);
	}

	public static final class ClaimedGenesis {
		private final Block block;
		private final WorldState worldState;
		private final Address receivedFrom;
		private final Instant receivedAt;

		private ClaimedGenesis(
				Block block,
				WorldState worldState,
				Address receivedFrom,
				Instant receivedAt) {
			if (block == null || worldState == null || receivedFrom == null || receivedAt == null) {
				throw new IllegalArgumentException("Claimed genesis fields cannot be null");
			}
			this.block = block;
			this.worldState = worldState;
			this.receivedFrom = receivedFrom;
			this.receivedAt = receivedAt;
		}

		public Block block() {
			return block;
		}

		public WorldState worldState() {
			return worldState;
		}

		public Address receivedFrom() {
			return receivedFrom;
		}

		public Instant receivedAt() {
			return receivedAt;
		}
	}
}
