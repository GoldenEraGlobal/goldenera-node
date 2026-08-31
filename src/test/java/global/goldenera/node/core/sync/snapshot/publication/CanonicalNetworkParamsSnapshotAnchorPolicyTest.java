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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Hash;
import org.apache.tuweni.bytes.Bytes;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class CanonicalNetworkParamsSnapshotAnchorPolicyTest {

	@Test
	void exactThirtySecondHistorySelectsExactly2880BlocksAndOverrideOnlyIncreasesLag() {
		Fixture fixture = new Fixture(10_000, 30_000, 30_000);

		SnapshotPublicationAnchor derived = fixture.policy().select(fixture.head).orElseThrow();
		assertThat(derived.lagBlocks()).isEqualTo(2_880);
		assertThat(derived.height()).isEqualTo(7_120);

		fixture.properties.setPublishMinimumLagBlocks(4_000);
		SnapshotPublicationAnchor overridden = fixture.policy().select(fixture.head).orElseThrow();
		assertThat(overridden.lagBlocks()).isEqualTo(4_000);
		assertThat(overridden.height()).isEqualTo(6_000);
	}

	@Test
	void intervalChangeAndFasterHistoryScanBackwardUntilTimestampIsOneDayOld() {
		Fixture intervalChanged = new Fixture(10_000, 60_000, 30_000);
		SnapshotPublicationAnchor changed = intervalChanged.policy().select(intervalChanged.head).orElseThrow();
		assertThat(changed.height()).isEqualTo(7_120);
		assertThat(changed.lagBlocks()).isEqualTo(2_880);

		Fixture fasterHistory = new Fixture(10_000, 30_000, 15_000);
		SnapshotPublicationAnchor faster = fasterHistory.policy().select(fasterHistory.head).orElseThrow();
		assertThat(faster.height()).isEqualTo(4_240);
		assertThat(faster.lagBlocks()).isEqualTo(5_760);
	}

	@Test
	void delayedHistoryKeepsMandatoryBlockLagEvenWhenItIsOlderThanOneDay() {
		Fixture fixture = new Fixture(10_000, 30_000, 60_000);

		SnapshotPublicationAnchor anchor = fixture.policy().select(fixture.head).orElseThrow();

		assertThat(anchor.height()).isEqualTo(7_120);
		assertThat(anchor.lagBlocks()).isEqualTo(2_880);
	}

	@Test
	void youngHeadAndMissingOrNonCanonicalHeadersFailAsNoOp() {
		Fixture young = new Fixture(1_000, 30_000, 30_000);
		assertThat(young.policy().select(young.head)).isEmpty();

		Fixture missing = new Fixture(10_000, 30_000, 30_000);
		missing.missingHeight = 7_120L;
		assertThat(missing.policy().select(missing.head)).isEmpty();

		Fixture reorged = new Fixture(10_000, 30_000, 30_000);
		reorged.nonCanonicalHeight = 7_120L;
		assertThat(reorged.policy().select(reorged.head)).isEmpty();
	}

	@Test
	void nonMonotonicOrInvalidCanonicalTimestampsFailAsNoOp() {
		Fixture nonMonotonic = new Fixture(10_000, 60_000, 30_000);
		nonMonotonic.timestampOverrides.put(8_000L, nonMonotonic.timestamp(8_001).plusSeconds(1));
		assertThat(nonMonotonic.policy().select(nonMonotonic.head)).isEmpty();

		Fixture invalid = new Fixture(10_000, 30_000, 30_000);
		invalid.timestampOverrides.put(7_120L, Instant.EPOCH.minusSeconds(1));
		assertThat(invalid.policy().select(invalid.head)).isEmpty();
	}

	private static final class Fixture {
		private static final Instant BASE = Instant.parse("2025-01-01T00:00:00Z");
		private final SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		private final ChainQuery chainQuery = mock(ChainQuery.class);
		private final WorldStateFactory worldStateFactory = mock(WorldStateFactory.class);
		private final long headHeight;
		private final long actualIntervalMillis;
		private final Map<Long, Instant> timestampOverrides = new HashMap<>();
		private Long missingHeight;
		private Long nonCanonicalHeight;
		private final StoredBlock head;

		private Fixture(long height, long currentIntervalMillis, long actualIntervalMillis) {
			this.headHeight = height;
			this.actualIntervalMillis = actualIntervalMillis;
			Hash stateRoot = Hash.fromHexString("0x" + "11".repeat(32));
			head = stored(height, stateRoot);
			WorldState state = mock(WorldState.class);
			NetworkParamsState params = mock(NetworkParamsState.class);
			when(worldStateFactory.createForValidation(stateRoot)).thenReturn(state);
			when(state.getParams()).thenReturn(params);
			when(params.getTargetMiningTimeMs()).thenReturn(currentIntervalMillis);
			when(chainQuery.getStoredBlockByHeight(anyLong())).thenAnswer(invocation -> {
				long requested = invocation.getArgument(0);
				return requested < 0 || requested > headHeight || Long.valueOf(requested).equals(missingHeight)
						? Optional.empty() : Optional.of(stored(requested, Hash.ZERO));
			});
			when(chainQuery.getBlockHashByHeight(anyLong())).thenAnswer(invocation -> {
				long requested = invocation.getArgument(0);
				return Long.valueOf(requested).equals(nonCanonicalHeight)
						? Optional.of(hash(requested + 1)) : Optional.of(hash(requested));
			});
		}

		private StoredBlock stored(long height, Hash stateRoot) {
			StoredBlock stored = mock(StoredBlock.class);
			Block block = mock(Block.class);
			BlockHeader header = mock(BlockHeader.class);
			Hash hash = hash(height);
			when(stored.getHeight()).thenReturn(height);
			when(stored.getHash()).thenReturn(hash);
			when(stored.getBlock()).thenReturn(block);
			when(block.getHeader()).thenReturn(header);
			when(header.getHeight()).thenReturn(height);
			when(header.getHash()).thenReturn(hash);
			when(header.getPreviousHash()).thenReturn(height == 0 ? Hash.ZERO : hash(height - 1));
			when(header.getStateRootHash()).thenReturn(stateRoot);
			when(header.getTimestamp()).thenAnswer(ignored ->
					timestampOverrides.getOrDefault(height, timestamp(height)));
			return stored;
		}

		private Instant timestamp(long height) {
			return BASE.plusMillis(Math.multiplyExact(height, actualIntervalMillis));
		}

		private Hash hash(long height) {
			return Hash.hash(Bytes.ofUnsignedLong(height));
		}

		private CanonicalNetworkParamsSnapshotAnchorPolicy policy() {
			return new CanonicalNetworkParamsSnapshotAnchorPolicy(properties, chainQuery, worldStateFactory);
		}
	}
}
