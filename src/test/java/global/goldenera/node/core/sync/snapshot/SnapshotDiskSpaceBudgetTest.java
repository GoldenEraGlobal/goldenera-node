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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotChunkCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityType;

class SnapshotDiskSpaceBudgetTest {

	@Test
	void budgetsManifestBoundUncompressedBytesInsteadOfHighlyCompressedTransportBytes() {
		CheckpointSnapshotManifest state = mock(CheckpointSnapshotManifest.class);
		when(state.chunks()).thenReturn(List.of(stateDescriptor(64L * 1024 * 1024)));
		CoreSnapshotArchiveManifest archive = archive(
				1024, 256L * 1024 * 1024,
				1024, 256L * 1024 * 1024);
		SnapshotDiskSpaceBudget budget = new SnapshotDiskSpaceBudget(path -> 512L * 1024 * 1024);

		assertThatThrownBy(() -> budget.require(
				Path.of("/budget-test"), state, archive, SnapshotDiskSpaceBudget.Purpose.VERIFICATION))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("Insufficient peak disk space")
				.hasMessageContaining("uncompressedPayload=603979780");
	}

	@Test
	void exposesBoundedWalAndCompactionReserveForLowSpaceSeams() {
		CheckpointSnapshotManifest state = mock(CheckpointSnapshotManifest.class);
		when(state.chunks()).thenReturn(List.of(stateDescriptor(1)));
		CoreSnapshotArchiveManifest archive = archive(1, 1, 1, 1);
		SnapshotDiskSpaceBudget spacious = new SnapshotDiskSpaceBudget(path -> Long.MAX_VALUE);

		SnapshotDiskSpaceBudget.Requirement requirement = spacious.require(
				Path.of("/budget-test"), state, archive,
				SnapshotDiskSpaceBudget.Purpose.PREPARED_DATABASE);

		assertThat(requirement.walReserveBytes())
				.isBetween(SnapshotDiskSpaceBudget.MIN_WAL_RESERVE_BYTES,
						SnapshotDiskSpaceBudget.MAX_WAL_RESERVE_BYTES);
		assertThat(requirement.compactionReserveBytes())
				.isBetween(SnapshotDiskSpaceBudget.MIN_COMPACTION_RESERVE_BYTES,
						SnapshotDiskSpaceBudget.MAX_COMPACTION_RESERVE_BYTES);
		SnapshotDiskSpaceBudget exact = new SnapshotDiskSpaceBudget(path -> requirement.requiredBytes());
		assertThat(exact.require(
				Path.of("/budget-test"), state, archive,
				SnapshotDiskSpaceBudget.Purpose.PREPARED_DATABASE).usableBytes())
				.isEqualTo(requirement.requiredBytes());
	}

	private SnapshotChunkDescriptor stateDescriptor(long uncompressedBytes) {
		return new SnapshotChunkDescriptor(
				0, "state", "https://snapshot.invalid/chunk/0", 0, uncompressedBytes,
				Hash.hash(Bytes.of(1)));
	}

	private CoreSnapshotArchiveManifest archive(
			long compressedArchiveBytes,
			long uncompressedArchiveBytes,
			long compressedEntityBytes,
			long uncompressedEntityBytes) {
		CoreSnapshotBlockChunkDescriptor block = new CoreSnapshotBlockChunkDescriptor(
				0, 0, 0, 1, CoreSnapshotChunkCompression.ZSTD,
				compressedArchiveBytes, Hash.ZERO, uncompressedArchiveBytes, Hash.ZERO);
		CoreSnapshotEntityChunkDescriptor entity = new CoreSnapshotEntityChunkDescriptor(
				0, CoreSnapshotEntityType.TOKEN, 0,
				compressedEntityBytes, Hash.ZERO, uncompressedEntityBytes, Hash.ZERO);
		return new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION, Hash.ZERO, List.of(block), List.of(entity));
	}
}
