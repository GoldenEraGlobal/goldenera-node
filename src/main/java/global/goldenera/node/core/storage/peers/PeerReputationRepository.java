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
package global.goldenera.node.core.storage.peers;

import static lombok.AccessLevel.PRIVATE;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.p2p.reputation.PeerReputationRecord;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Repository
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class PeerReputationRepository {

	private static final byte CURRENT_VERSION = 1;

	RocksDB peerReputationDB;
	PeerReputationColumnFamilies columnFamilies;

	public PeerReputationRepository(@Qualifier("peerReputationDB") RocksDB peerReputationDB,
			PeerReputationColumnFamilies columnFamilies) {
		this.peerReputationDB = peerReputationDB;
		this.columnFamilies = columnFamilies;
	}

	public Optional<PeerReputationRecord> find(Address identity) {
		if (identity == null) {
			return Optional.empty();
		}
		try {
			byte[] value = peerReputationDB.get(columnFamilies.peerReputation(), key(identity));
			if (value == null) {
				return Optional.empty();
			}
			return Optional.of(deserialize(value));
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to load peer reputation from RocksDB", e);
		}
	}

	public PeerReputationRecord save(@NonNull Address identity, @NonNull PeerReputationRecord record) {
		try {
			peerReputationDB.put(columnFamilies.peerReputation(), key(identity), serialize(record));
			return record;
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to persist peer reputation", e);
		}
	}

	private byte[] key(Address identity) {
		return identity.toChecksumAddress().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
	}

	private byte[] serialize(PeerReputationRecord record) {
		ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + Long.BYTES + Long.BYTES)
				.order(ByteOrder.BIG_ENDIAN);
		buffer.put(CURRENT_VERSION);
		buffer.putInt(record.failureCount());
		buffer.putLong(record.lastFailureEpochSecond());
		buffer.putLong(record.lastSuccessEpochSecond());
		return buffer.array();
	}

	private PeerReputationRecord deserialize(byte[] data) {
		ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
		byte version = buffer.get();
		if (version != CURRENT_VERSION) {
			throw new IllegalStateException("Unsupported peer reputation record version: " + version);
		}
		int failureCount = buffer.getInt();
		long lastFailure = buffer.getLong();
		long lastSuccess = buffer.getLong();
		return new PeerReputationRecord(failureCount, lastFailure, lastSuccess);
	}
}
