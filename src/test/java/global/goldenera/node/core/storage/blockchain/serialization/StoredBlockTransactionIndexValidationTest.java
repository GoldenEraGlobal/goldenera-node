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
package global.goldenera.node.core.storage.blockchain.serialization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.block.BlockEncoder;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock.TxIndex;
import global.goldenera.rlp.RLP;

class StoredBlockTransactionIndexValidationTest {

	@Test
	void rejectsSerializedEmptyHashIndexWithNonEmptySizeIndex() {
		TxIndex malformedIndex = mock(TxIndex.class);
		when(malformedIndex.getHashes()).thenReturn(new Hash[0]);
		when(malformedIndex.getSizes()).thenReturn(new int[] { 1 });
		when(malformedIndex.getSenders()).thenReturn(new Address[0]);
		StoredBlock malformed = storedBlock(malformedIndex, List.of());
		Bytes encoded = encodeUnchecked(malformed);

		assertThatThrownBy(() -> StoredBlockDecoder.INSTANCE.decode(encoded))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("different lengths");
	}

	@Test
	void refusesToSerializeMetadataThatDoesNotBelongToTheBlockTransaction() throws Exception {
		Tx transaction = transaction();
		assertRejected(transaction,
				Hash.fromHexString("0x" + "03".repeat(32)), transaction.getSize(), transaction.getSender());
		assertRejected(transaction, transaction.getHash(), transaction.getSize() + 1, transaction.getSender());
		assertRejected(transaction, transaction.getHash(), transaction.getSize(), Address.ZERO);
	}

	private Tx transaction() throws Exception {
		PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString("0x" + "01".repeat(32)));
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(Instant.ofEpochSecond(1))
				.recipient(Address.fromHexString("0x" + "02".repeat(20)))
				.amount(Wei.valueOf(1))
				.fee(Wei.valueOf(1))
				.nonce(0)
				.sign(key);
	}

	private void assertRejected(Tx transaction, Hash hash, int size, Address sender) {
		TxIndex malformedIndex = mock(TxIndex.class);
		when(malformedIndex.getHashes()).thenReturn(new Hash[] { hash });
		when(malformedIndex.getSizes()).thenReturn(new int[] { size });
		when(malformedIndex.getSenders()).thenReturn(new Address[] { sender });
		StoredBlock malformed = storedBlock(malformedIndex, List.of(transaction));

		assertThatThrownBy(() -> StoredBlockEncoder.INSTANCE.encode(malformed, StoredBlockVersion.V1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not match the block body at transaction 0");
	}

	private StoredBlock storedBlock(TxIndex txIndex, List<Tx> transactions) {
		BlockHeader header = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(0)
				.timestamp(Instant.ofEpochSecond(1))
				.previousHash(Hash.ZERO)
				.txRootHash(TxRootUtil.txRootHash(transactions))
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.ZERO)
				.nonce(0)
				.signature(Signature.ZERO)
				.build();
		Block block = BlockImpl.builder().header(header).txs(transactions).build();
		return StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(BigInteger.ONE)
				.receivedAt(header.getTimestamp())
				.receivedFrom(Address.ZERO)
				.connectedSource(ConnectedSource.GENESIS)
				.identity(Address.ZERO)
				.txIndex(txIndex)
				.computeIndexes()
				.build();
	}

	private Bytes encodeUnchecked(StoredBlock storedBlock) {
		return RLP.encode(out -> {
			out.startList();
			out.writeLongScalar(StoredBlockVersion.V1.getCode());
			out.writeRaw(BlockEncoder.INSTANCE.encode(storedBlock.getBlock(), true));
			out.writeBigIntegerScalar(storedBlock.getCumulativeDifficulty());
			out.writeLongScalar(storedBlock.getReceivedAt().toEpochMilli());
			out.writeBytes(storedBlock.getReceivedFrom());
			out.writeIntScalar(storedBlock.getConnectedSource().getCode());
			out.writeIntScalar(storedBlock.getBlockSize());
			out.writeBytes(storedBlock.getIdentity());
			out.writeBytes32(storedBlock.getHash());
			out.startList();
			for (Hash hash : storedBlock.getTransactionHashes()) {
				out.writeBytes32(hash);
			}
			out.endList();
			out.startList();
			for (int size : storedBlock.getTransactionSizes()) {
				out.writeIntScalar(size);
			}
			out.endList();
			out.startList();
			for (Address sender : storedBlock.getTransactionSenders()) {
				out.writeBytes(sender);
			}
			out.endList();
			BlockEventEncoder.INSTANCE.encodeList(out, storedBlock.getEvents());
			out.endList();
		});
	}
}
