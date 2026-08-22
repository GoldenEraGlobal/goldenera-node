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
package global.goldenera.node.core.sync.snapshot.operator;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository.UndoAction;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository.UndoType;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.chainidentity.RocksChainIdentityStore;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentityCodec;

/** Captures a bounded consistent live-head RocksDB checkpoint and proves its binding. */
public final class LiveHeadCoreSnapshotCloneService {

	private final RocksDB liveDatabase;
	private final RocksDbColumnFamilies liveFamilies;
	private final BlockchainDbProperties databaseProperties;
	private final BlockchainRocksDbFactory rocksDbFactory;
	private final ReentrantLock masterChainLock;
	private final ObjectMapper objectMapper;

	public LiveHeadCoreSnapshotCloneService(
			RocksDB liveDatabase,
			RocksDbColumnFamilies liveFamilies,
			BlockchainDbProperties databaseProperties,
			BlockchainRocksDbFactory rocksDbFactory,
			ReentrantLock masterChainLock,
			ObjectMapper objectMapper) {
		this.liveDatabase = Objects.requireNonNull(liveDatabase, "liveDatabase");
		this.liveFamilies = Objects.requireNonNull(liveFamilies, "liveFamilies");
		this.databaseProperties = Objects.requireNonNull(databaseProperties, "databaseProperties");
		this.rocksDbFactory = Objects.requireNonNull(rocksDbFactory, "rocksDbFactory");
		this.masterChainLock = Objects.requireNonNull(masterChainLock, "masterChainLock");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	public LiveHeadCoreSnapshotClone create() throws Exception {
		return create(-1, null);
	}

	public LiveHeadCoreSnapshotClone create(long snapshotHeight, Hash expectedSnapshotHash) throws Exception {
		Path livePath = Path.of(databaseProperties.getPath()).toAbsolutePath().normalize();
		Path parent = Objects.requireNonNull(livePath.getParent(), "Blockchain database parent").toRealPath();
		Path clonePath = parent.resolve("." + livePath.getFileName() + "-live-head-" + UUID.randomUUID());
		CapturedHead captured;
		CapturedHead selected;
		masterChainLock.lock();
		try {
			captured = capture(liveDatabase, liveFamilies);
			long selectedHeight = snapshotHeight < 0 ? captured.height() : snapshotHeight;
			if (selectedHeight < 0 || selectedHeight > captured.height()) {
				throw new IllegalStateException("Requested snapshot height is outside the captured canonical chain");
			}
			selected = captureAt(liveDatabase, liveFamilies, selectedHeight, captured.identity());
			if (expectedSnapshotHash != null && !expectedSnapshotHash.equals(selected.hash())) {
				throw new IllegalStateException("Requested snapshot hash changed before RocksDB checkpoint capture");
			}
			try (Checkpoint checkpoint = Checkpoint.create(liveDatabase)) {
				checkpoint.createCheckpoint(clonePath.toString());
			}
		} finally {
			masterChainLock.unlock();
		}

		RocksDbColumnFamilies cloneFamilies = new RocksDbColumnFamilies();
		RocksDB cloneDatabase = null;
		boolean complete = false;
		try {
			cloneDatabase = rocksDbFactory.open(clonePath, cloneFamilies);
			CapturedHead cloned = capture(cloneDatabase, cloneFamilies);
			if (!captured.equals(cloned)) {
				throw new IllegalStateException("RocksDB checkpoint head/identity binding is inconsistent");
			}
			if (selected.height() < captured.height()) {
				rewind(cloneDatabase, cloneFamilies, captured.height(), selected);
			}
			CapturedHead rewound = capture(cloneDatabase, cloneFamilies);
			if (!selected.equals(rewound)) {
				throw new IllegalStateException("Isolated RocksDB clone did not reach the selected snapshot anchor");
			}
			if (!selected.stateRoot().equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)
					&& cloneDatabase.get(cloneFamilies.stateTrie(), selected.stateRoot().toArray()) == null) {
				throw new IllegalStateException("RocksDB checkpoint is missing its captured state root");
			}
			closeDatabase(cloneDatabase, cloneFamilies);
			cloneDatabase = null;
			complete = true;
			return new LiveHeadCoreSnapshotClone(
					clonePath, selected.height(), selected.hash(), selected.stateRoot(),
					selected.cumulativeDifficulty(), selected.identity());
		} finally {
			if (cloneDatabase != null) {
				closeDatabase(cloneDatabase, cloneFamilies);
			}
			if (!complete) {
				deleteDirectory(clonePath);
			}
		}
	}

	public boolean isStillCanonical(long height, Hash expectedHash) {
		Objects.requireNonNull(expectedHash, "expectedHash");
		masterChainLock.lock();
		try {
			byte[] canonical = liveDatabase.get(
					liveFamilies.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
			return canonical != null && canonical.length == Hash.SIZE
					&& Hash.wrap(canonical).equals(expectedHash);
		} catch (RocksDBException e) {
			throw new IllegalStateException("Cannot recheck live snapshot anchor canonicality", e);
		} finally {
			masterChainLock.unlock();
		}
	}

	private CapturedHead capture(RocksDB database, RocksDbColumnFamilies families) throws RocksDBException {
		byte[] headHash = database.get(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH);
		byte[] identityBytes = database.get(families.metadata(), RocksChainIdentityStore.STORAGE_KEY);
		if (headHash == null || headHash.length != Hash.SIZE || identityBytes == null) {
			throw new IllegalStateException("Live canonical head or chain identity metadata is missing");
		}
		byte[] encodedBlock = database.get(families.blocks(), headHash);
		if (encodedBlock == null) {
			throw new IllegalStateException("Live canonical head block is missing");
		}
		StoredBlock head = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encodedBlock));
		Hash hash = Hash.wrap(headHash);
		if (!head.getHash().equals(hash)) {
			throw new IllegalStateException("Live canonical head metadata is inconsistent");
		}
		StoredChainIdentity identity = StoredChainIdentityCodec.decode(identityBytes);
		return new CapturedHead(
				head.getHeight(), hash, head.getBlock().getHeader().getStateRootHash(),
				head.getCumulativeDifficulty(), identity);
	}

	private CapturedHead captureAt(
			RocksDB database,
			RocksDbColumnFamilies families,
			long height,
			StoredChainIdentity identity) throws RocksDBException {
		byte[] hash = database.get(families.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
		if (hash == null || hash.length != Hash.SIZE) {
			throw new IllegalStateException("Selected canonical snapshot height is missing");
		}
		byte[] encoded = database.get(families.blocks(), hash);
		StoredBlock block = encoded == null ? null : StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encoded));
		if (block == null || block.getHeight() != height || !block.getHash().equals(Hash.wrap(hash))) {
			throw new IllegalStateException("Selected canonical snapshot block is inconsistent");
		}
		return new CapturedHead(
				height, block.getHash(), block.getBlock().getHeader().getStateRootHash(),
				block.getCumulativeDifficulty(), identity);
	}

	private void rewind(
			RocksDB database,
			RocksDbColumnFamilies families,
			long capturedHeight,
			CapturedHead selected) throws Exception {
		try (WriteOptions options = new WriteOptions().setSync(false)) {
			for (long height = capturedHeight; height > selected.height(); height--) {
				byte[] hashBytes = database.get(families.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
				if (hashBytes == null) {
					throw new IllegalStateException("Clone rollback canonical height is missing: " + height);
				}
				Hash hash = Hash.wrap(hashBytes);
				byte[] encodedBlock = database.get(families.blocks(), hashBytes);
				StoredBlock block = encodedBlock == null ? null
						: StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encodedBlock));
				if (block == null || block.getHeight() != height) {
					throw new IllegalStateException("Clone rollback StoredBlock is missing or corrupt");
				}
				try (WriteBatch batch = new WriteBatch()) {
					applyUndo(database, families, batch, hash);
					for (Tx transaction : block.getBlock().getTxs()) {
						batch.delete(families.txIndex(), transaction.getHash().toArray());
					}
					batch.delete(families.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
					batch.delete(families.blocks(), hashBytes);
					batch.delete(families.entityUndoLog(), hashBytes);
					database.write(options, batch);
				}
			}
		}
		try (WriteOptions options = new WriteOptions().setSync(true); WriteBatch metadata = new WriteBatch()) {
			metadata.put(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, selected.hash().toArray());
			database.write(options, metadata);
		}
	}

	private void applyUndo(
			RocksDB database,
			RocksDbColumnFamilies families,
			WriteBatch batch,
			Hash hash) throws Exception {
		byte[] undoBytes = database.get(families.entityUndoLog(), hash.toArray());
		if (undoBytes == null) {
			return;
		}
		UndoAction[] actions = objectMapper.readValue(undoBytes, UndoAction[].class);
		Set<String> unique = new HashSet<>();
		for (UndoAction action : actions) {
			if (action == null || action.type == null || action.address == null
					|| !unique.add(action.type + ":" + action.address)) {
				throw new IllegalStateException("Clone entity undo log is invalid or duplicated");
			}
			Address address = Address.fromHexString(action.address);
			ColumnFamilyHandle family = switch (action.type) {
				case TOKEN -> families.tokens();
				case AUTHORITY -> families.authorities();
				case VALIDATOR -> families.validators();
			};
			if (action.oldValue == null) {
				batch.delete(family, address.toArray());
			} else {
				validateUndo(action.type, action.oldValue);
				batch.put(family, address.toArray(), action.oldValue);
			}
		}
	}

	private void validateUndo(UndoType type, byte[] bytes) throws IOException {
		switch (type) {
			case TOKEN -> objectMapper.readValue(bytes, TokenStateImpl.class);
			case AUTHORITY -> objectMapper.readValue(bytes, AuthorityStateImpl.class);
			case VALIDATOR -> objectMapper.readValue(bytes, ValidatorStateImpl.class);
		}
	}

	private void closeDatabase(RocksDB database, RocksDbColumnFamilies families) {
		families.getHandles().values().forEach(ColumnFamilyHandle::close);
		database.close();
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private record CapturedHead(
			long height,
			Hash hash,
			Hash stateRoot,
			BigInteger cumulativeDifficulty,
			StoredChainIdentity identity) {
	}
}
