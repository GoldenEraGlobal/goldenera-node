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
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;

/** Independent downgrade and resource-bound contract for negotiated sync windows. */
class BlockSyncWindowAdversarialContractTest {

	@Test
	void publishedLegacyPeerCannotBeUpgradedByASecondOrSpoofedCapabilitySnapshot() {
		RemotePeer legacy = peer();
		assertThat(legacy.negotiatedHeaderPageLimit()).isEqualTo(1_000);
		legacy.completeCapabilityNegotiation(List.of());

		assertThatThrownBy(() -> legacy.completeCapabilityNegotiation(
				List.of(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already negotiated");
		assertThatThrownBy(() -> legacy.completeCapabilityNegotiation(List.of("block-sync-v2 ")))
				.isInstanceOf(IllegalStateException.class);
		assertThat(legacy.negotiatedHeaderPageLimit())
				.isEqualTo(P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT);
	}

	@Test
	void negotiatedV2SnapshotIsIdempotentAndCannotBeDowngraded() {
		RemotePeer peer = peer();
		List<String> v2 = List.of(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY);
		peer.completeCapabilityNegotiation(v2);
		peer.completeCapabilityNegotiation(v2);

		assertThatThrownBy(() -> peer.completeCapabilityNegotiation(List.of()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already negotiated");
		assertThat(peer.negotiatedHeaderPageLimit())
				.isEqualTo(P2PSyncProtocol.V2_HEADER_PAGE_LIMIT);
	}

	@Test
	void onlyExactCapabilityTokenRaisesThePageLimit() {
		for (String spoof : List.of("sync-v2", "BLOCK-SYNC-V2", "block-sync-v02")) {
			RemotePeer peer = peer();
			peer.completeCapabilityNegotiation(List.of(spoof));
			assertThat(peer.negotiatedHeaderPageLimit())
					.as("spoof token %s", spoof)
					.isEqualTo(P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT);
		}
	}

	@Test
	void negotiatedHeaderAndLegacyBodyBoundsRemainIndependent() {
		assertThat(P2PSyncProtocol.INTERNAL_VALIDATION_WINDOW_HEADERS).isEqualTo(1_000);
		assertThat(P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT).isEqualTo(1_000);
		assertThat(P2PSyncProtocol.V2_HEADER_PAGE_LIMIT).isEqualTo(4_096);
		assertThat(P2PSyncProtocol.MAX_LOCAL_HEADER_WINDOW).isEqualTo(4_096);
		assertThat(BlockSyncManagerService.calculateBodyBatchSize()).isBetween(1, 7);
	}

	@Test
	void oversizedAndCumulativeHeaderPagesFailBeforeValidationCanReceiveThem() {
		assertThatThrownBy(() -> BlockSyncManagerService.requireHeaderPageWithinBudget(
				1_001, P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT, 0, 1_000))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("header page budget");
		assertThatThrownBy(() -> BlockSyncManagerService.requireHeaderPageWithinBudget(
				4_097, P2PSyncProtocol.V2_HEADER_PAGE_LIMIT, 0, 4_096))
				.isInstanceOf(GEValidationException.class);
		assertThatThrownBy(() -> BlockSyncManagerService.requireHeaderPageWithinBudget(
				97, 100, 4_000, 4_096))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("header page budget");
		assertThatThrownBy(() -> BlockSyncManagerService.requireHeaderPageWithinBudget(
				0, 1, 0, 4_097))
				.isInstanceOf(GEValidationException.class);
		assertThatThrownBy(() -> BlockSyncManagerService.requireHeaderPageWithinBudget(
				0, 101, 0, 100))
				.isInstanceOf(GEValidationException.class);

		BlockSyncManagerService.requireHeaderPageWithinBudget(96, 100, 4_000, 4_096);
	}

	@Test
	void negotiatedCapsImplyAConstantFourPageAndSevenBodyRequestCeiling() {
		assertThat(P2PSyncProtocol.MAX_LOCAL_HEADER_WINDOW
				/ P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT).isEqualTo(4);
		assertThat(P2PSyncProtocol.MAX_LOCAL_HEADER_WINDOW
				/ P2PSyncProtocol.V2_HEADER_PAGE_LIMIT).isEqualTo(1);
		assertThat(BlockSyncManagerService.calculateBodyBatchSize()).isLessThanOrEqualTo(7);
	}

	@Test
	void adaptivePrefetchRequiresStableFullWindowsAndCollapsesInTail() {
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				9_100, 10_000, ProofOfWorkVerificationMode.RANDOMX_FULL, 10)).isEqualTo(1);
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_LIGHT, 10)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_FULL, 1)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_FULL, 2)).isEqualTo(4);
	}

	@Test
	void staleOrReorgedCanonicalHeadCannotMatchPrefetchedWindowAnchor() {
		Hash expected = Hash.fromHexString("0x" + "11".repeat(32));
		StoredBlock committed = mock(StoredBlock.class);
		when(committed.getHeight()).thenReturn(500L);
		when(committed.getHash()).thenReturn(expected);

		assertThat(BlockSyncManagerService.matchesCommittedWindow(500L, expected, committed)).isTrue();
		assertThat(BlockSyncManagerService.matchesCommittedWindow(499L, expected, committed)).isFalse();
		assertThat(BlockSyncManagerService.matchesCommittedWindow(
				500L, Hash.fromHexString("0x" + "22".repeat(32)), committed)).isFalse();
		assertThat(BlockSyncManagerService.matchesCommittedWindow(500L, expected, null)).isFalse();
	}

	private RemotePeer peer() {
		return new RemotePeer(mock(Channel.class), new SimpleMeterRegistry());
	}
}
