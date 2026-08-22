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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.rocksdb.ColumnFamilyHandle;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorCodec;

class CoreSnapshotCheckpointFloorStoreTest {

	@Test
	void returnsEmptyForLegacyDatabaseWithoutFloorMetadata() throws Exception {
		Fixture fixture = fixture(null);

		assertThat(fixture.store.load()).isEmpty();
	}

	@Test
	void strictlyDecodesCanonicalFloorMetadata() throws Exception {
		CoreSnapshotCheckpointFloor floor = floor();
		Fixture fixture = fixture(CoreSnapshotCheckpointFloorCodec.encode(floor));

		assertThat(fixture.store.load()).contains(floor);
	}

	@Test
	void rejectsNonCanonicalFloorMetadata() throws Exception {
		byte[] canonical = CoreSnapshotCheckpointFloorCodec.encode(floor());
		byte[] malformed = Arrays.copyOf(canonical, canonical.length + 1);
		Fixture fixture = fixture(malformed);

		assertThatThrownBy(fixture.store::load)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("metadata is invalid");
	}

	private Fixture fixture(byte[] encoded) throws Exception {
		RocksDBRepository repository = mock(RocksDBRepository.class);
		RocksDbColumnFamilies families = mock(RocksDbColumnFamilies.class);
		ColumnFamilyHandle metadata = mock(ColumnFamilyHandle.class);
		when(families.metadata()).thenReturn(metadata);
		when(repository.get(metadata, CoreSnapshotCheckpointFloorCodec.STORAGE_KEY)).thenReturn(encoded);
		return new Fixture(new CoreSnapshotCheckpointFloorStore(repository, families));
	}

	private CoreSnapshotCheckpointFloor floor() {
		return new CoreSnapshotCheckpointFloor(
				100L,
				hash(1),
				hash(2),
				BigInteger.valueOf(1_000L),
				hash(3),
				hash(4));
	}

	private Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}

	private record Fixture(CoreSnapshotCheckpointFloorStore store) {
	}
}
