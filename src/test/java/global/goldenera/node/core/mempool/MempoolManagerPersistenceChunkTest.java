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
package global.goldenera.node.core.mempool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationBatch;

class MempoolManagerPersistenceChunkTest {

	@Test
	void fortyMegabytePeerResponseIsSplitWithoutChangingTransactionOrder() {
		long encodedBytesPerTransaction = 80L * 1024L;
		List<Tx> transactions = IntStream.range(0, 500)
				.mapToObj(ignored -> mock(Tx.class))
				.toList();

		List<List<Tx>> chunks = MempoolManager.persistenceChunks(
				transactions, ignored -> encodedBytesPerTransaction);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(transactions);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.size() * encodedBytesPerTransaction)
				.isLessThanOrEqualTo(MempoolMutationBatch.MAX_RAW_ADMISSION_BYTES_PER_BATCH));
	}
}
