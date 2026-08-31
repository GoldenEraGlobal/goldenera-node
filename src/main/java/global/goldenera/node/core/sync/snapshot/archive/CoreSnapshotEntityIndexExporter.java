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
package global.goldenera.node.core.sync.snapshot.archive;

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_ENTRIES_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_COMPRESSED_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_TOTAL_ENTRIES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_TOTAL_UNCOMPRESSED_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_UNCOMPRESSED_CHUNK_BYTES;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.SnapshotExportException;

/** Writes deterministic checkpoint entity indexes as independently verified Zstd chunks. */
public final class CoreSnapshotEntityIndexExporter {

	private static final long DEFAULT_TARGET_UNCOMPRESSED_BYTES = 32L * 1024 * 1024;

	private final long targetUncompressedBytes;

	public CoreSnapshotEntityIndexExporter() {
		this(DEFAULT_TARGET_UNCOMPRESSED_BYTES);
	}

	CoreSnapshotEntityIndexExporter(long targetUncompressedBytes) {
		if (targetUncompressedBytes <= CoreSnapshotEntityChunkCodec.HEADER_BYTES
				|| targetUncompressedBytes > MAX_UNCOMPRESSED_CHUNK_BYTES) {
			throw new IllegalArgumentException("Entity chunk target is outside supported limits");
		}
		this.targetUncompressedBytes = targetUncompressedBytes;
	}

	public ExportResult export(CoreSnapshotEntityIndexSource source, Path outputDirectory) {
		Objects.requireNonNull(source, "source");
		Path output = validateOutputDirectory(outputDirectory);
		List<CoreSnapshotEntityChunkDescriptor> descriptors = new ArrayList<>();
		List<Path> files = new ArrayList<>();
		long totalEntries = 0;
		try {
			totalEntries = Math.addExact(totalEntries, writeType(
					CoreSnapshotEntityType.TOKEN, source.tokens(),
					CoreSnapshotEntityStateCodec::encodeToken, output, descriptors, files));
			totalEntries = Math.addExact(totalEntries, writeType(
					CoreSnapshotEntityType.AUTHORITY, source.authorities(),
					CoreSnapshotEntityStateCodec::encodeAuthority, output, descriptors, files));
			totalEntries = Math.addExact(totalEntries, writeType(
					CoreSnapshotEntityType.VALIDATOR, source.validators(),
					CoreSnapshotEntityStateCodec::encodeValidator, output, descriptors, files));
			if (totalEntries > MAX_TOTAL_ENTRIES) {
				throw failure("Entity indexes exceed total entry limit");
			}
			long totalUncompressedBytes = 0;
			for (CoreSnapshotEntityChunkDescriptor descriptor : descriptors) {
				totalUncompressedBytes = Math.addExact(
						totalUncompressedBytes, descriptor.uncompressedByteCount());
			}
			if (totalUncompressedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
				throw failure("Entity indexes exceed total uncompressed byte limit");
			}
			return new ExportResult(descriptors, files, totalEntries);
		} catch (SnapshotExportException e) {
			cleanup(files);
			throw e;
		} catch (Exception e) {
			cleanup(files);
			throw failure("Entity sidecar export failed", e);
		}
	}

	private <T> long writeType(
			CoreSnapshotEntityType type,
			Map<Address, T> states,
			Function<T, Bytes> encoder,
			Path output,
			List<CoreSnapshotEntityChunkDescriptor> descriptors,
			List<Path> files) throws IOException {
		Objects.requireNonNull(states, type + " states");
		List<Map.Entry<Address, T>> ordered = states.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(addressComparator()))
				.toList();
		int offset = 0;
		do {
			int end = chunkEnd(ordered, offset, encoder);
			List<Map.Entry<Address, T>> chunk = ordered.subList(offset, end);
			writeChunk(type, chunk, encoder, output, descriptors, files);
			offset = end;
		} while (offset < ordered.size());
		return ordered.size();
	}

	private <T> int chunkEnd(
			List<Map.Entry<Address, T>> ordered, int offset, Function<T, Bytes> encoder) {
		if (ordered.isEmpty()) {
			return 0;
		}
		long bytes = CoreSnapshotEntityChunkCodec.HEADER_BYTES;
		int end = offset;
		while (end < ordered.size() && end - offset < MAX_ENTRIES_PER_CHUNK) {
			Bytes encoded = canonical(encoder.apply(ordered.get(end).getValue()));
			long next = Math.addExact(bytes, Address.SIZE + Integer.BYTES + (long) encoded.size());
			if (end > offset && next > targetUncompressedBytes) {
				break;
			}
			if (next > MAX_UNCOMPRESSED_CHUNK_BYTES) {
				throw failure("One entity entry cannot fit in a bounded chunk");
			}
			bytes = next;
			end++;
		}
		return end;
	}

	private <T> void writeChunk(
			CoreSnapshotEntityType type,
			List<Map.Entry<Address, T>> entries,
			Function<T, Bytes> encoder,
			Path output,
			List<CoreSnapshotEntityChunkDescriptor> descriptors,
			List<Path> files) throws IOException {
		int index = descriptors.size();
		Path raw = output.resolve("entity-chunk-%05d.raw.part".formatted(index));
		Path compressedPart = output.resolve("entity-chunk-%05d.zst.part".formatted(index));
		Path compressed = output.resolve("entity-chunk-%05d.zst".formatted(index));
		try {
			try (OutputStream file = new BufferedOutputStream(
					Files.newOutputStream(raw, CREATE_NEW, WRITE));
					DataOutputStream data = new DataOutputStream(file)) {
				CoreSnapshotEntityChunkCodec.writeHeader(data, index, type, entries.size());
				for (Map.Entry<Address, T> entry : entries) {
					Bytes state = canonical(encoder.apply(entry.getValue()));
					CoreSnapshotEntityStateCodec.decodeCanonical(type, state);
					CoreSnapshotEntityChunkCodec.writeEntry(
							data, new CoreSnapshotEntityEntry(entry.getKey(), state));
				}
			}
			forceFile(raw);
			long rawBytes = Files.size(raw);
			Hash rawHash = hashFile(raw);
			try (InputStream input = new BufferedInputStream(Files.newInputStream(raw, READ));
					OutputStream target = new BufferedOutputStream(
							Files.newOutputStream(compressedPart, CREATE_NEW, WRITE))) {
				CoreSnapshotCompression.writeZstd(input, target);
			}
			forceFile(compressedPart);
			long compressedBytes = Files.size(compressedPart);
			if (compressedBytes <= 0 || compressedBytes > MAX_COMPRESSED_CHUNK_BYTES) {
				throw failure("Compressed entity chunk exceeds snapshot limits");
			}
			Hash compressedHash = hashFile(compressedPart);
			Files.move(compressedPart, compressed, ATOMIC_MOVE);
			CoreSnapshotEntityChunkDescriptor descriptor = new CoreSnapshotEntityChunkDescriptor(
					index, type, entries.size(), compressedBytes, compressedHash, rawBytes, rawHash);
			descriptors.add(descriptor);
			files.add(compressed);
		} finally {
			Files.deleteIfExists(raw);
			Files.deleteIfExists(compressedPart);
		}
	}

	private Bytes canonical(Bytes encoded) {
		Bytes value = Objects.requireNonNull(encoded, "canonical entity state");
		if (value.isEmpty()) {
			throw failure("Canonical entity state cannot be empty");
		}
		return value;
	}

	private Comparator<Address> addressComparator() {
		return (left, right) -> Arrays.compareUnsigned(left.toArray(), right.toArray());
	}

	private Path validateOutputDirectory(Path output) {
		Objects.requireNonNull(output, "outputDirectory");
		try {
			if (!output.isAbsolute() || !output.equals(output.normalize())
					|| !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(output)) {
				throw failure("Entity sidecar output must be an existing real absolute directory");
			}
			return output.toRealPath(LinkOption.NOFOLLOW_LINKS);
		} catch (IOException e) {
			throw failure("Cannot validate entity sidecar output", e);
		}
	}

	private Hash hashFile(Path file) throws IOException {
		Digest256 digest = new Digest256();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = new BufferedInputStream(Files.newInputStream(file, READ))) {
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return Hash.wrap(digest.digest());
	}

	private void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private void cleanup(List<Path> files) {
		for (int index = files.size() - 1; index >= 0; index--) {
			try {
				Files.deleteIfExists(files.get(index));
			} catch (IOException ignored) {
				// Preserve the export failure.
			}
		}
	}

	private SnapshotExportException failure(String message) {
		return new SnapshotExportException(message);
	}

	private SnapshotExportException failure(String message, Throwable cause) {
		return new SnapshotExportException(message, cause);
	}

	public record ExportResult(
			List<CoreSnapshotEntityChunkDescriptor> descriptors,
			List<Path> chunkFiles,
			long totalEntries) {

		public ExportResult {
			descriptors = List.copyOf(descriptors);
			chunkFiles = List.copyOf(chunkFiles);
		}
	}
}
