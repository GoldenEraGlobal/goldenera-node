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
package global.goldenera.node.core.sync.snapshot.publication;

import java.time.Instant;
import java.util.Optional;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

/** Derives the minimum 24-hour lag from the canonical head's network parameters. */
public final class CanonicalNetworkParamsSnapshotAnchorPolicy implements SnapshotPublicationAnchorPolicy {

	private final SnapshotDistributionProperties properties;
	private final ChainQuery chainQuery;
	private final WorldStateFactory worldStateFactory;
	private final SnapshotSafetyLagCalculator lagCalculator;

	public CanonicalNetworkParamsSnapshotAnchorPolicy(
			SnapshotDistributionProperties properties,
			ChainQuery chainQuery,
			WorldStateFactory worldStateFactory) {
		this.properties = properties;
		this.chainQuery = chainQuery;
		this.worldStateFactory = worldStateFactory;
		this.lagCalculator = new SnapshotSafetyLagCalculator();
	}

	@Override
	public Optional<SnapshotPublicationAnchor> select(StoredBlock canonicalHead) {
		try {
			BlockHeader headHeader = canonicalHead.getBlock().getHeader();
			Instant headTimestamp = validTimestamp(headHeader);
			long intervalMillis = worldStateFactory.createForValidation(
					headHeader.getStateRootHash()).getParams().getTargetMiningTimeMs();
			long minimumLagBlocks = lagCalculator.lagBlocks(
					intervalMillis, properties.getPublishMinimumLagBlocks());
			if (canonicalHead.getHeight() < minimumLagBlocks) {
				return Optional.empty();
			}
			Instant cutoff = headTimestamp.minus(SnapshotSafetyLagCalculator.MINIMUM_SAFETY_LAG);
			long anchorHeight = canonicalHead.getHeight() - minimumLagBlocks;
			StoredBlock anchor = canonical(anchorHeight);
			if (anchor == null) {
				return Optional.empty();
			}
			Instant laterTimestamp = validTimestamp(anchor.getBlock().getHeader());
			if (laterTimestamp.isAfter(headTimestamp)) {
				return Optional.empty();
			}
			while (laterTimestamp.isAfter(cutoff)) {
				if (anchorHeight == 0) {
					return Optional.empty();
				}
				StoredBlock previous = canonical(anchorHeight - 1);
				if (previous == null
						|| !anchor.getBlock().getHeader().getPreviousHash().equals(previous.getHash())) {
					return Optional.empty();
				}
				Instant previousTimestamp = validTimestamp(previous.getBlock().getHeader());
				if (previousTimestamp.isAfter(laterTimestamp)) {
					return Optional.empty();
				}
				anchor = previous;
				anchorHeight--;
				laterTimestamp = previousTimestamp;
			}
			long actualLagBlocks = canonicalHead.getHeight() - anchorHeight;
			return Optional.of(new SnapshotPublicationAnchor(anchorHeight, anchor.getHash(), actualLagBlocks));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private StoredBlock canonical(long height) {
		StoredBlock stored = chainQuery.getStoredBlockByHeight(height).orElse(null);
		Hash indexed = chainQuery.getBlockHashByHeight(height).orElse(null);
		if (stored == null || stored.getBlock() == null || stored.getBlock().getHeader() == null
				|| stored.getHeight() != height || indexed == null || !indexed.equals(stored.getHash())
				|| stored.getBlock().getHeader().getHeight() != height
				|| !stored.getBlock().getHeader().getHash().equals(stored.getHash())) {
			return null;
		}
		return stored;
	}

	private Instant validTimestamp(BlockHeader header) {
		Instant timestamp = header == null ? null : header.getTimestamp();
		if (timestamp == null || timestamp.isBefore(Instant.EPOCH)) {
			throw new IllegalStateException("Canonical snapshot timestamp is invalid");
		}
		return timestamp;
	}
}
