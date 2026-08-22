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
package global.goldenera.node.core.sync.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotChunkCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityType;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotManifest;

class SnapshotFormatCompatibilityTest {

	@Test
	void freezesCurrentProducerVersionsAndRetainsAllPublishedReaderVersions() {
		assertThat(CheckpointSnapshotLimits.FORMAT_VERSION)
				.isEqualTo(SnapshotFormatCompatibility.CURRENT_STATE_FORMAT);
		assertThat(CoreSnapshotArchiveLimits.FORMAT_VERSION)
				.isEqualTo(SnapshotFormatCompatibility.CURRENT_ARCHIVE_FORMAT);
		assertThat(ExplorerSnapshotManifest.FORMAT_VERSION)
				.isEqualTo(SnapshotFormatCompatibility.CURRENT_EXPLORER_FORMAT);

		assertThat(SnapshotFormatCompatibility.SUPPORTED_STATE_READER_FORMATS).contains(1);
		assertThat(SnapshotFormatCompatibility.SUPPORTED_ARCHIVE_READER_FORMATS).contains(2);
		assertThat(SnapshotFormatCompatibility.SUPPORTED_ENTITY_READER_FORMATS).contains(1);
		assertThat(SnapshotFormatCompatibility.SUPPORTED_EXPLORER_READER_FORMATS).contains(1);
		assertThat(SnapshotFormatCompatibility.supportsState(0)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsState(2)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsArchive(1)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsArchive(3)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsEntity(0)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsEntity(2)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsExplorer(0)).isFalse();
		assertThat(SnapshotFormatCompatibility.supportsExplorer(2)).isFalse();
	}

	@Test
	void decodesFrozenCurrentStateAndArchiveManifestFixtures() {
		CheckpointSnapshotManifest state = stateManifest();
		Bytes stateBytes = CheckpointSnapshotManifestCodec.canonicalBytes(state);
		assertThat(Hash.hash(stateBytes).toHexString())
				.isEqualTo("0x438034a29fbeb6640015d35f5022d953cdda2e151850c148b641b53a55d54a44");
		assertThat(CheckpointSnapshotManifestCodec.decodeCanonicalBytes(stateBytes)).isEqualTo(state);

		CoreSnapshotArchiveManifest archive = archiveManifest(state);
		Bytes archiveBytes = CoreSnapshotArchiveManifestCodec.canonicalBytes(archive);
		assertThat(Hash.hash(archiveBytes).toHexString())
				.isEqualTo("0x4ed006d27bdae0ca68107dcbef2c5aa310c4be48445394765bf3398be647b3eb");
		assertThat(CoreSnapshotArchiveManifestCodec.decodeCanonicalBytes(archiveBytes)).isEqualTo(archive);
	}

	private CheckpointSnapshotManifest stateManifest() {
		StoredChainIdentity identity = new StoredChainIdentity(
				1, 1, "golden-format", "0x" + "11".repeat(32), "22".repeat(32));
		return new CheckpointSnapshotManifest(
				SnapshotFormatCompatibility.CURRENT_STATE_FORMAT, 1, identity, 42,
				Hash.fromHexString("0x" + "33".repeat(32)),
				Hash.fromHexString("0x" + "44".repeat(32)), BigInteger.valueOf(12_345),
				new SnapshotHeaderSegment(Hash.fromHexString("0x" + "55".repeat(32)),
						BigInteger.valueOf(12_000), List.of()),
				List.of(new SnapshotChunkDescriptor(
						0, "chunk-0", "https://snapshot.invalid/chunks/0", 7, 128,
						Hash.fromHexString("0x" + "66".repeat(32)))));
	}

	private CoreSnapshotArchiveManifest archiveManifest(CheckpointSnapshotManifest state) {
		return new CoreSnapshotArchiveManifest(
				SnapshotFormatCompatibility.CURRENT_ARCHIVE_FORMAT,
				CheckpointSnapshotManifestCodec.signingHash(state),
				List.of(new CoreSnapshotBlockChunkDescriptor(
						0, 0, 42, 43, CoreSnapshotChunkCompression.ZSTD, 512,
						Hash.fromHexString("0x" + "77".repeat(32)), 1_024,
						Hash.fromHexString("0x" + "88".repeat(32)))),
				List.of(new CoreSnapshotEntityChunkDescriptor(
						0, CoreSnapshotEntityType.TOKEN, 3, 256,
						Hash.fromHexString("0x" + "99".repeat(32)), 768,
						Hash.fromHexString("0x" + "aa".repeat(32)))));
	}
}
