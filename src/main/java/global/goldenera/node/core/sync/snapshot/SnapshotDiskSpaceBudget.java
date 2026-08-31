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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;

/** Conservative peak-space guard based only on manifest-bound uncompressed data. */
public final class SnapshotDiskSpaceBudget {

	static final long MIN_WAL_RESERVE_BYTES = 64L * 1024 * 1024;
	static final long MAX_WAL_RESERVE_BYTES = 1024L * 1024 * 1024;
	static final long MIN_COMPACTION_RESERVE_BYTES = 256L * 1024 * 1024;
	static final long MAX_COMPACTION_RESERVE_BYTES = 8L * 1024 * 1024 * 1024;

	private final UsableSpace usableSpace;

	public SnapshotDiskSpaceBudget(UsableSpace usableSpace) {
		this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
	}

	public static SnapshotDiskSpaceBudget system() {
		return new SnapshotDiskSpaceBudget(path -> Files.getFileStore(existingPath(path)).getUsableSpace());
	}

	public Requirement requireVerification(
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest archiveManifest) {
		Path temporaryFilesystem = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		return require(temporaryFilesystem, stateManifest, archiveManifest, Purpose.VERIFICATION);
	}

	public Requirement requirePreparedDatabase(
			Path databaseParent,
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest archiveManifest) {
		return require(databaseParent, stateManifest, archiveManifest, Purpose.PREPARED_DATABASE);
	}

	Requirement require(
			Path filesystemPath,
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest archiveManifest,
			Purpose purpose) {
		Objects.requireNonNull(filesystemPath, "filesystemPath");
		Objects.requireNonNull(stateManifest, "stateManifest");
		Objects.requireNonNull(purpose, "purpose");
		PayloadTotals totals = totals(stateManifest, archiveManifest);
		long walReserve = boundedFraction(
				totals.uncompressedBytes(), 20, MIN_WAL_RESERVE_BYTES, MAX_WAL_RESERVE_BYTES);
		long compactionReserve = boundedFraction(
				totals.uncompressedBytes(), 3,
				MIN_COMPACTION_RESERVE_BYTES, MAX_COMPACTION_RESERVE_BYTES);
		long required;
		try {
			required = Math.addExact(totals.uncompressedBytes(), Math.addExact(walReserve, compactionReserve));
		} catch (ArithmeticException e) {
			throw new SnapshotVerificationException("Snapshot peak disk-space budget overflow", e);
		}
		long usable;
		try {
			usable = usableSpace.bytes(filesystemPath);
		} catch (IOException e) {
			throw new SnapshotVerificationException(
					"Cannot inspect snapshot " + purpose.label + " filesystem capacity", e);
		}
		Requirement requirement = new Requirement(
				purpose, totals, walReserve, compactionReserve, required, usable, filesystemPath);
		if (usable < required) {
			throw new SnapshotVerificationException(
					"Insufficient peak disk space for snapshot " + purpose.label
							+ ": required=" + required + ", usable=" + usable
							+ ", uncompressedPayload=" + totals.uncompressedBytes());
		}
		return requirement;
	}

	static PayloadTotals totals(
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest archiveManifest) {
		long stateBytes = 0;
		for (SnapshotChunkDescriptor descriptor : stateManifest.chunks()) {
			long recordBytes;
			try {
				recordBytes = Math.addExact(
						Integer.BYTES,
						Math.addExact(descriptor.byteCount(), Math.multiplyExact(36L, descriptor.nodeCount())));
			} catch (ArithmeticException e) {
				throw new SnapshotVerificationException("Snapshot state chunk byte total overflow", e);
			}
			stateBytes = add(stateBytes, recordBytes);
		}
		long archiveBytes = 0;
		long entityBytes = 0;
		if (archiveManifest != null) {
			for (CoreSnapshotBlockChunkDescriptor descriptor : archiveManifest.blockChunks()) {
				archiveBytes = add(archiveBytes, descriptor.uncompressedByteCount());
			}
			for (CoreSnapshotEntityChunkDescriptor descriptor : archiveManifest.entityChunks()) {
				entityBytes = add(entityBytes, descriptor.uncompressedByteCount());
			}
		}
		return new PayloadTotals(stateBytes, archiveBytes, entityBytes, add(add(stateBytes, archiveBytes), entityBytes));
	}

	private static long boundedFraction(long value, long divisor, long minimum, long maximum) {
		long fraction = value / divisor + (value % divisor == 0 ? 0 : 1);
		return Math.min(maximum, Math.max(minimum, fraction));
	}

	private static long add(long left, long right) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException e) {
			throw new SnapshotVerificationException("Snapshot uncompressed byte total overflow", e);
		}
	}

	private static Path existingPath(Path requested) throws IOException {
		Path current = requested.toAbsolutePath().normalize();
		while (current != null && Files.notExists(current)) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IOException("Snapshot filesystem path has no existing ancestor");
		}
		return current;
	}

	public enum Purpose {
		VERIFICATION("verification"),
		PREPARED_DATABASE("prepared database");

		private final String label;

		Purpose(String label) {
			this.label = label;
		}
	}

	@FunctionalInterface
	public interface UsableSpace {
		long bytes(Path filesystemPath) throws IOException;
	}

	public record PayloadTotals(
			long stateBytes,
			long archiveBytes,
			long entityBytes,
			long uncompressedBytes) {
	}

	public record Requirement(
			Purpose purpose,
			PayloadTotals payload,
			long walReserveBytes,
			long compactionReserveBytes,
			long requiredBytes,
			long usableBytes,
			Path filesystemPath) {
	}
}
