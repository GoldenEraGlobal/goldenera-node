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
package global.goldenera.node.core.blockchain.validation;

import java.util.List;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import lombok.NonNull;

/** Creates immutable value snapshots at validation and persistence boundaries. */
public final class ImmutableBlockSnapshot {

	private ImmutableBlockSnapshot() {
	}

	public static Block copyOf(@NonNull Block source) {
		BlockHeader sourceHeader = source.getHeader();
		if (sourceHeader == null || source.getTxs() == null) {
			throw new IllegalArgumentException("Full block snapshot requires a header and transaction list");
		}

		BlockHeader header = BlockHeaderImpl.builder()
				.version(sourceHeader.getVersion())
				.height(sourceHeader.getHeight())
				.timestamp(sourceHeader.getTimestamp())
				.previousHash(sourceHeader.getPreviousHash())
				.txRootHash(sourceHeader.getTxRootHash())
				.stateRootHash(sourceHeader.getStateRootHash())
				.difficulty(sourceHeader.getDifficulty())
				.coinbase(sourceHeader.getCoinbase())
				.nonce(sourceHeader.getNonce())
				.signature(sourceHeader.getSignature())
				.build();
		List<Tx> transactions = List.copyOf(source.getTxs());
		Block snapshot = BlockImpl.builder().header(header).txs(transactions).build();
		if (!snapshot.getHash().equals(source.getHash())) {
			throw new IllegalArgumentException("Block changed while its immutable snapshot was being created");
		}
		return snapshot;
	}
}
