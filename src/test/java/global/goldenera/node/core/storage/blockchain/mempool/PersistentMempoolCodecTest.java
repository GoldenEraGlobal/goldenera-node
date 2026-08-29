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
package global.goldenera.node.core.storage.blockchain.mempool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;

class PersistentMempoolCodecTest {

	@Test
	void roundTripsStrictVersionedActiveRecord() throws Exception {
		Tx transaction = transaction(1, 4L);
		StoredMempoolTransaction record = record(
				transaction, 123L, MempoolAdmissionReason.REORG, hash('a'));

		StoredMempoolTransaction decoded = new PersistentMempoolCodec().decode(
				new PersistentMempoolCodec().encode(record));

		assertThat(decoded).usingRecursiveComparison().isEqualTo(record);
		assertThat(decoded.rawSignedTx()).isEqualTo(record.rawSignedTx());
	}

	@Test
	void rejectsTrailingBytesAndHashMismatch() throws Exception {
		Tx transaction = transaction(2, 5L);
		StoredMempoolTransaction record = record(
				transaction, -1L, MempoolAdmissionReason.NEW, null);
		byte[] encoded = new PersistentMempoolCodec().encode(record);
		byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);

		assertThatThrownBy(() -> new PersistentMempoolCodec().decode(trailing))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredMempoolTransaction(
				1, StoredMempoolStatus.ACTIVE, hash('f'), record.rawSignedTx(),
				Instant.EPOCH, -1L, MempoolAdmissionReason.NEW, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("hash-mismatched");
	}

	@Test
	void enforcesRawTransactionAndMutationBatchHardLimits() {
		assertThatThrownBy(() -> new StoredMempoolTransaction(
				1, StoredMempoolStatus.ACTIVE, Hash.ZERO,
				new byte[StoredMempoolTransaction.MAX_RAW_TX_BYTES + 1],
				Instant.EPOCH, -1L, MempoolAdmissionReason.NEW, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("size");
		List<MempoolStateMutation> tooMany = IntStream
				.range(0, MempoolMutationBatch.MAX_MUTATIONS + 1)
				.<MempoolStateMutation>mapToObj(index -> MempoolStateMutation.delete(
						Hash.fromHexString(String.format("0x%064x", index + 1)), null))
				.toList();
		assertThatThrownBy(() -> new MempoolMutationBatch(UUID.randomUUID(), tooMany))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("size");
	}

	private StoredMempoolTransaction record(
			Tx transaction,
			long firstSeenHeight,
			MempoolAdmissionReason reason,
			Hash replaces) {
		return new StoredMempoolTransaction(
				StoredMempoolTransaction.CURRENT_VERSION,
				StoredMempoolStatus.ACTIVE,
				transaction.getHash(),
				TxEncoder.INSTANCE.encode(transaction, true).toArray(),
				Instant.parse("2026-08-29T12:00:00Z"),
				firstSeenHeight,
				reason,
				replaces);
	}

	private Tx transaction(int privateKey, long nonce) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(Instant.parse("2026-08-29T12:00:00Z"))
				.recipient(Address.fromHexString(String.format("0x%040x", privateKey + 100)))
				.amount(Wei.valueOf(BigInteger.ONE))
				.fee(Wei.valueOf(BigInteger.ONE))
				.nonce(nonce)
				.sign(PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", privateKey))));
	}

	private Hash hash(char digit) {
		return Hash.fromHexString("0x" + String.valueOf(digit).repeat(64));
	}
}
