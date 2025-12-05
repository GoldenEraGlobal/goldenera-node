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
package global.goldenera.node.core.storage.blockchain.serialization.impl.encoding;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.block.BlockEncoder;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncodingStrategy;
import global.goldenera.rlp.RLPOutput;

public class StoredBlockV1EncodingStrategy implements StoredBlockEncodingStrategy {

	@Override
	public void encode(RLPOutput out, StoredBlock storedBlock) {
		Bytes blockBytes = BlockEncoder.INSTANCE.encode(storedBlock.getBlock(), true);
		out.writeRaw(blockBytes);
		out.writeBigIntegerScalar(storedBlock.getCumulativeDifficulty());
		out.writeLongScalar(storedBlock.getReceivedAt().toEpochMilli());
		out.writeBytes(storedBlock.getReceivedFrom());
		out.writeIntScalar(storedBlock.getConnectedSource().getCode());

		out.writeIntScalar(storedBlock.getBlockSize());
		out.writeBytes32(storedBlock.getHash());

		Hash[] txHashes = storedBlock.getTransactionHashes();
		out.startList();
		if (txHashes != null && txHashes.length > 0) {
			for (Hash txHash : txHashes) {
				out.writeBytes32(txHash);
			}
		}
		out.endList();

		int[] txSizes = storedBlock.getTransactionSizes();
		out.startList();
		if (txSizes != null && txSizes.length > 0) {
			for (int size : txSizes) {
				out.writeIntScalar(size);
			}
		}
		out.endList();

		Address[] txSenders = storedBlock.getTransactionSenders();
		out.startList();
		if (txSenders != null && txSenders.length > 0) {
			for (Address sender : txSenders) {
				out.writeBytes(sender);
			}
		}
		out.endList();
	}
}
