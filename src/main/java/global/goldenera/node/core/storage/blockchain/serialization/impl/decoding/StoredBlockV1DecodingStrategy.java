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
package global.goldenera.node.core.storage.blockchain.serialization.impl.decoding;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.block.BlockDecoder;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.BlockEventDecoder;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecodingStrategy;
import global.goldenera.rlp.RLPInput;

public class StoredBlockV1DecodingStrategy implements StoredBlockDecodingStrategy {

	@Override
	public StoredBlock decode(RLPInput input, boolean withoutBody) {
		Bytes blockBytes = input.readRaw();
		Block block = BlockDecoder.INSTANCE.decode(blockBytes, withoutBody);
		BigInteger cumulativeDifficulty = input.readBigIntegerScalar();
		long receivedAtMillis = input.readLongScalar();
		Instant receivedAt = Instant.ofEpochMilli(receivedAtMillis);
		Address receivedFrom = Address.wrap(input.readBytes());
		int connectedSourceCode = input.readIntScalar();
		ConnectedSource connectedSource = ConnectedSource.fromCode(connectedSourceCode);

		int blockSize = input.readIntScalar();
		Address identity = Address.wrap(input.readBytes());

		Hash hash = Hash.wrap(input.readBytes32());

		// Read transaction hashes array - use list size for exact allocation
		int hashCount = input.enterList();
		Hash[] hashes = new Hash[hashCount];
		for (int i = 0; i < hashCount; i++) {
			hashes[i] = Hash.wrap(input.readBytes32());
		}
		input.leaveList();

		// Read transaction sizes array
		int sizesCount = input.enterList();
		int[] sizes = new int[sizesCount];
		for (int i = 0; i < sizesCount; i++) {
			sizes[i] = input.readIntScalar();
		}
		input.leaveList();

		// Read transaction senders array
		int sendersCount = input.enterList();
		Address[] senders = new Address[sendersCount];
		for (int i = 0; i < sendersCount; i++) {
			senders[i] = Address.wrap(input.readBytes());
		}
		input.leaveList();

		// Create TxIndex from stored data (derives hash maps internally)
		StoredBlock.TxIndex txIndex = StoredBlock.TxIndex.fromStored(hashes, sizes, senders);

		// Read block events (invisible state changes)
		List<BlockEvent> events = BlockEventDecoder.INSTANCE.decodeList(input);

		return StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(cumulativeDifficulty)
				.receivedAt(receivedAt)
				.receivedFrom(receivedFrom)
				.connectedSource(connectedSource)
				.isPartial(withoutBody)
				.hash(hash)
				.blockSize(blockSize)
				.txIndex(txIndex)
				.events(events)
				.identity(identity)
				.build();
	}

	@Override
	public StoredBlock decode(RLPInput input) {
		return decode(input, false);
	}
}
