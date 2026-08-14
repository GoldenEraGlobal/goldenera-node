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
package global.goldenera.node.core.storage.chainidentity;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;

/**
 * Opens an already-existing blockchain database read-only. It deliberately does
 * not depend on the application's writable {@link RocksDB} bean: that bean is
 * not authorized to create the path or missing column families until this
 * preflight has completed.
 */
public final class RocksChainStoragePreflightProbe implements ChainStoragePreflightProbe {

	private static final byte[] GENESIS_HEIGHT_KEY = Bytes.ofUnsignedLong(0).toArray();

	private final Path databasePath;

	public RocksChainStoragePreflightProbe(Path databasePath) {
		this.databasePath = databasePath.toAbsolutePath().normalize();
	}

	@Override
	public ChainStoragePreflightObservation inspect() {
		if (Files.notExists(databasePath)) {
			return emptyObservation();
		}
		if (!Files.isDirectory(databasePath)) {
			throw rejected("RocksDB path exists but is not a directory");
		}
		if (directoryIsEmpty()) {
			return emptyObservation();
		}

		RocksDB.loadLibrary();
		List<byte[]> familyNames = listColumnFamilies();
		List<ColumnFamilyOptions> familyOptions = new ArrayList<>(familyNames.size());
		List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(familyNames.size());
		for (byte[] familyName : familyNames) {
			ColumnFamilyOptions options = new ColumnFamilyOptions();
			familyOptions.add(options);
			descriptors.add(new ColumnFamilyDescriptor(familyName, options));
		}

		List<ColumnFamilyHandle> handles = new ArrayList<>(familyNames.size());
		try (DBOptions options = new DBOptions()
				.setCreateIfMissing(false)
				.setCreateMissingColumnFamilies(false);
				RocksDB database = RocksDB.openReadOnly(
						options, databasePath.toString(), descriptors, handles)) {
			Map<String, ColumnFamilyHandle> families = namedFamilies(familyNames, handles);
			Optional<StoredChainIdentity> identity = readIdentity(database, families);
			boolean hasChainData = hasChainData(database, families);
			Optional<String> genesisHash = readAndVerifyGenesis(database, families);
			return new ChainStoragePreflightObservation(
					"RocksDB", families.containsKey(RocksDbColumnFamilies.CF_METADATA),
					identity, hasChainData, genesisHash);
		} catch (RocksDBException | RuntimeException e) {
			if (e instanceof ChainStorageGuardException guardException) {
				throw guardException;
			}
			throw rejected("Failed to inspect the existing RocksDB without mutation", e);
		} finally {
			for (int index = handles.size() - 1; index >= 0; index--) {
				handles.get(index).close();
			}
			familyOptions.forEach(ColumnFamilyOptions::close);
		}
	}

	private ChainStoragePreflightObservation emptyObservation() {
		return new ChainStoragePreflightObservation(
				"RocksDB", false, Optional.empty(), false, Optional.empty());
	}

	private boolean directoryIsEmpty() {
		try (var entries = Files.list(databasePath)) {
			return entries.findAny().isEmpty();
		} catch (Exception e) {
			throw rejected("Failed to inspect the RocksDB directory", e);
		}
	}

	private List<byte[]> listColumnFamilies() {
		try (Options options = new Options().setCreateIfMissing(false)) {
			List<byte[]> names = RocksDB.listColumnFamilies(options, databasePath.toString());
			if (names.isEmpty()) {
				throw rejected("Existing RocksDB has no column families");
			}
			return names;
		} catch (RocksDBException e) {
			throw rejected("Existing RocksDB metadata is missing or corrupt", e);
		}
	}

	private Map<String, ColumnFamilyHandle> namedFamilies(
			List<byte[]> familyNames, List<ColumnFamilyHandle> handles) {
		if (familyNames.size() != handles.size()) {
			throw rejected("RocksDB returned an inconsistent column-family handle set");
		}
		Map<String, ColumnFamilyHandle> families = new LinkedHashMap<>();
		for (int index = 0; index < familyNames.size(); index++) {
			String name = new String(familyNames.get(index), UTF_8);
			if (families.put(name, handles.get(index)) != null) {
				throw rejected("RocksDB contains duplicate column-family names");
			}
		}
		return families;
	}

	private Optional<StoredChainIdentity> readIdentity(
			RocksDB database, Map<String, ColumnFamilyHandle> families) throws RocksDBException {
		ColumnFamilyHandle metadata = families.get(RocksDbColumnFamilies.CF_METADATA);
		if (metadata == null) {
			return Optional.empty();
		}
		byte[] encoded = database.get(metadata, RocksChainIdentityStore.STORAGE_KEY);
		if (encoded == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(StoredChainIdentityCodec.decode(encoded));
		} catch (IllegalArgumentException e) {
			throw rejected("RocksDB chain identity metadata is corrupt", e);
		}
	}

	private boolean hasChainData(
			RocksDB database, Map<String, ColumnFamilyHandle> families) throws RocksDBException {
		for (Map.Entry<String, ColumnFamilyHandle> entry : families.entrySet()) {
			byte[] excludedKey = RocksDbColumnFamilies.CF_METADATA.equals(entry.getKey())
					? RocksChainIdentityStore.STORAGE_KEY
					: null;
			if (hasAnyEntry(database, entry.getValue(), excludedKey)) {
				return true;
			}
		}
		return false;
	}

	private Optional<String> readAndVerifyGenesis(
			RocksDB database, Map<String, ColumnFamilyHandle> families) throws RocksDBException {
		ColumnFamilyHandle heightIndex = families.get(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT);
		if (heightIndex == null) {
			return Optional.empty();
		}
		byte[] indexedHash = database.get(heightIndex, GENESIS_HEIGHT_KEY);
		if (indexedHash == null) {
			return Optional.empty();
		}
		if (indexedHash.length != Hash.SIZE) {
			throw rejected("RocksDB genesis height index contains an invalid hash");
		}

		ColumnFamilyHandle blocks = families.get(RocksDbColumnFamilies.CF_BLOCKS);
		if (blocks == null) {
			throw rejected("RocksDB genesis index exists but the blocks column family is missing");
		}
		byte[] encodedBlock = database.get(blocks, indexedHash);
		if (encodedBlock == null) {
			throw rejected("RocksDB genesis index does not resolve to a stored block");
		}

		StoredBlock storedBlock;
		try {
			storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encodedBlock));
		} catch (RuntimeException e) {
			throw rejected("RocksDB genesis block cannot be decoded", e);
		}
		Hash expectedHash = Hash.wrap(indexedHash);
		if (storedBlock.getBlock() == null || storedBlock.getBlock().getHeader() == null) {
			throw rejected("RocksDB genesis block is missing its header or body");
		}
		if (storedBlock.isPartial() || storedBlock.getBlock().getTxs() == null) {
			throw rejected("RocksDB genesis block body is missing");
		}
		if (storedBlock.getHeight() != 0
				|| !expectedHash.equals(storedBlock.getHash())
				|| !expectedHash.equals(storedBlock.getBlock().getHash())) {
			throw rejected("RocksDB genesis block, stored hash, and height index are inconsistent");
		}
		if (storedBlock.getTxCount() != storedBlock.getBlock().getTxs().size()) {
			throw rejected("RocksDB genesis block transaction index is inconsistent with its body");
		}
		return Optional.of("0x" + HexFormat.of().formatHex(indexedHash));
	}

	private boolean hasAnyEntry(
			RocksDB database, ColumnFamilyHandle family, byte[] excludedKey) throws RocksDBException {
		try (RocksIterator iterator = database.newIterator(family)) {
			iterator.seekToFirst();
			while (iterator.isValid()) {
				if (excludedKey == null || !Arrays.equals(excludedKey, iterator.key())) {
					return true;
				}
				iterator.next();
			}
			iterator.status();
			return false;
		}
	}

	private ChainStorageGuardException rejected(String message) {
		return new ChainStorageGuardException(message + " at " + databasePath);
	}

	private ChainStorageGuardException rejected(String message, Throwable cause) {
		return new ChainStorageGuardException(message + " at " + databasePath, cause);
	}
}
