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
package global.goldenera.node.core.blockchain.difficulty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;

class DifficultyCalculatorBranchTest {

	@Test
	void nonCanonicalParentResolvesAsertAnchorFromItsOwnBranch() {
		ChainQuery chainQuery = mock(ChainQuery.class);
		DifficultyCalculator calculator = new DifficultyCalculator(chainQuery);
		BlockHeader anchor = header(10, hash(10), hash(9), 100, 1_000);
		BlockHeader parent = header(11, hash(11), hash(10), 100, 31_000);
		NetworkParamsState params = params(10);
		StoredBlock storedAnchor = stored(anchor);

		when(chainQuery.getCanonicalStoredBlockHeaderByHash(parent.getHash())).thenReturn(Optional.empty());
		when(chainQuery.getStoredBlockHeaderByHash(anchor.getHash())).thenReturn(Optional.of(storedAnchor));

		BigInteger actual = calculator.calculateNextDifficulty(parent, params);
		BigInteger expected = DifficultyUtil.calculateAbsoluteAsertDifficulty(
				anchor.getDifficulty(), 30_000, 1, 30_000, 64L * 30_000, BigInteger.ONE);

		assertThat(actual).isEqualTo(expected);
		verify(chainQuery, never()).getStoredBlockHeaderByHeight(10);
	}

	@Test
	void explicitCandidateAnchorNeverReadsTheLosingCanonicalHeight() {
		ChainQuery chainQuery = mock(ChainQuery.class);
		DifficultyCalculator calculator = new DifficultyCalculator(chainQuery);
		BlockHeader candidateAnchor = header(20, hash(20), hash(19), 250, 50_000);
		BlockHeader parent = header(21, hash(21), hash(20), 250, 80_000);

		BigInteger actual = calculator.calculateNextDifficulty(parent, params(20), candidateAnchor);

		assertThat(actual).isEqualTo(DifficultyUtil.calculateAbsoluteAsertDifficulty(
				candidateAnchor.getDifficulty(), 30_000, 1, 30_000, 64L * 30_000, BigInteger.ONE));
		verify(chainQuery, never()).getStoredBlockHeaderByHeight(20);
	}

	@Test
	void missingCandidateAnchorFailsClosedInsteadOfUsingParentDifficulty() {
		ChainQuery chainQuery = mock(ChainQuery.class);
		DifficultyCalculator calculator = new DifficultyCalculator(chainQuery);
		BlockHeader parent = header(11, hash(11), hash(10), 100, 31_000);
		when(chainQuery.getCanonicalStoredBlockHeaderByHash(parent.getHash())).thenReturn(Optional.empty());
		when(chainQuery.getStoredBlockHeaderByHash(parent.getPreviousHash())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> calculator.calculateNextDifficulty(parent, params(10)))
				.isInstanceOf(GEFailedException.class)
				.hasMessageContaining("missing block 10");
	}

	private NetworkParamsState params(long anchorHeight) {
		NetworkParamsState params = mock(NetworkParamsState.class);
		when(params.getAsertAnchorHeight()).thenReturn(anchorHeight);
		when(params.getTargetMiningTimeMs()).thenReturn(30_000L);
		when(params.getAsertHalfLifeBlocks()).thenReturn(64L);
		when(params.getMinDifficulty()).thenReturn(BigInteger.ONE);
		return params;
	}

	private BlockHeader header(long height, Hash hash, Hash previousHash, long difficulty, long timestampMillis) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getHash()).thenReturn(hash);
		when(header.getPreviousHash()).thenReturn(previousHash);
		when(header.getDifficulty()).thenReturn(BigInteger.valueOf(difficulty));
		when(header.getTimestamp()).thenReturn(Instant.ofEpochMilli(timestampMillis));
		return header;
	}

	private StoredBlock stored(BlockHeader header) {
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getBlock()).thenReturn(block);
		return stored;
	}

	private Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}
}
