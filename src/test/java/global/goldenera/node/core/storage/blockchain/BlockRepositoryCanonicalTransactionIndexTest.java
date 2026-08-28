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
package global.goldenera.node.core.storage.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import com.github.benmanes.caffeine.cache.Caffeine;

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
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.domain.TxCacheEntry;

class BlockRepositoryCanonicalTransactionIndexTest {

	private static final PrivateKey SENDER = key(1);
	private static final Address RECIPIENT = key(2).getAddress();

	@TempDir
	Path temporaryDirectory;

	@Test
	void forkStorageCannotHideCanonicalTransactionAndDisconnectRemovesItsMapping() throws Exception {
		Path databasePath = temporaryDirectory.resolve("blockchain");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(databasePath)).open(databasePath, families);
		try {
			BlockRepository repository = repository(new RocksDBRepository(database, families), families);
			Tx transaction = transfer();
			StoredBlock parent = stored(block(0, Hash.ZERO, List.of(), 0), BigInteger.ONE);
			StoredBlock canonical = stored(block(1, parent.getHash(), List.of(transaction), 1), BigInteger.TWO);
			StoredBlock fork = stored(block(1, parent.getHash(), List.of(transaction), 2), BigInteger.TWO);

			repository.executeAtomicBatch(batch -> {
				repository.addBlockToBatch(batch, parent);
				repository.addBlockToBatch(batch, canonical);
			});
			assertThat(repository.getTransactionBlockHash(transaction.getHash())).contains(canonical.getHash());

			repository.executeAtomicBatch(batch -> repository.saveForkBlockDataToBatch(batch, fork));
			assertThat(repository.getTransactionBlockHash(transaction.getHash())).contains(canonical.getHash());

			repository.executeAtomicBatch(batch ->
					repository.addDisconnectBlockIndexToBatch(batch, canonical, parent));
			assertThat(repository.getTransactionBlockHash(transaction.getHash())).isEmpty();
			repository.cacheTxEntry(TxCacheEntry.builder()
					.tx(transaction)
					.blockHash(canonical.getHash())
					.blockHeight(canonical.getHeight())
					.blockTimestamp(canonical.getBlock().getHeader().getTimestamp().toEpochMilli())
					.txIndex(0)
					.sender(transaction.getSender())
					.size(transaction.getSize())
					.build());
			assertThat(new ChainQuery(repository).getTransactionByHash(transaction.getHash())).isEmpty();

			repository.executeAtomicBatch(batch -> repository.addBlockToBatch(batch, fork));
			assertThat(repository.getTransactionBlockHash(transaction.getHash())).contains(fork.getHash());
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	private BlockRepository repository(RocksDBRepository rocks, RocksDbColumnFamilies families) {
		return new BlockRepository(
				rocks,
				families,
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build());
	}

	private Tx transfer() throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.recipient(RECIPIENT)
				.amount(Wei.valueOf(1))
				.fee(Wei.valueOf(1))
				.nonce(0)
				.sign(SENDER);
	}

	private Block block(long height, Hash previousHash, List<Tx> transactions, long nonce) {
		BlockHeader header = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(Instant.ofEpochSecond(1_000 + nonce))
				.previousHash(previousHash)
				.txRootHash(TxRootUtil.txRootHash(transactions))
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(RECIPIENT)
				.nonce(nonce)
				.signature(Signature.ZERO)
				.build();
		return BlockImpl.builder().header(header).txs(transactions).build();
	}

	private StoredBlock stored(Block block, BigInteger cumulativeDifficulty) {
		return StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(cumulativeDifficulty)
				.identity(SENDER.getAddress())
				.receivedAt(block.getHeader().getTimestamp())
				.receivedFrom(SENDER.getAddress())
				.connectedSource(ConnectedSource.SYNC)
				.computeIndexes()
				.events(List.of())
				.build();
	}

	private BlockchainDbProperties properties(Path databasePath) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(databasePath.toString());
		properties.setRocksdbBlockCacheMb(1);
		properties.setRocksdbWriteBufferMb(1);
		properties.setRocksdbMaxWriteBuffers(2);
		properties.setRocksdbMaxBackgroundJobs(1);
		properties.setRocksdbBlockSizeKb(4);
		properties.setRocksdbDirectReads(false);
		properties.setRocksdbDirectWrites(false);
		properties.setRocksdbBlobEnabled(false);
		return properties;
	}

	private static PrivateKey key(int value) {
		return PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", value)));
	}
}
