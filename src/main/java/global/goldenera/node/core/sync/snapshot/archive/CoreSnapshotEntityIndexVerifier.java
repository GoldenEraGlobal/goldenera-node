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

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_CHUNK_COUNT;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_COMPRESSED_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_ENTRIES_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_TOTAL_ENTRIES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_TOTAL_UNCOMPRESSED_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_UNCOMPRESSED_CHUNK_BYTES;

import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.NodeLoader;
import global.goldenera.merkletrie.patricia.StoredMerklePatriciaTrie;
import global.goldenera.merkletrie.patricia.StoredNodeFactory;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotCompression.VerifiedInputStream;

/** Verifies materialized entity indexes against the already verified checkpoint trie. */
public final class CoreSnapshotEntityIndexVerifier {

	public VerificationResult verify(
			Bytes32 checkpointStateRoot,
			NodeLoader nodeLoader,
			List<CoreSnapshotEntityChunkDescriptor> descriptors,
			CoreSnapshotEntityChunkSource chunkSource) {
		Objects.requireNonNull(checkpointStateRoot, "checkpointStateRoot");
		Objects.requireNonNull(nodeLoader, "nodeLoader");
		Objects.requireNonNull(descriptors, "descriptors");
		Objects.requireNonNull(chunkSource, "chunkSource");
		DescriptorTotals declared = verifyDescriptors(descriptors);
		Map<CoreSnapshotEntityType, MerkleTrie<Bytes, Bytes>> tries = entityTries(
				checkpointStateRoot, nodeLoader);
		EnumMap<CoreSnapshotEntityType, Long> actualCounts = new EnumMap<>(CoreSnapshotEntityType.class);
		for (CoreSnapshotEntityType type : CoreSnapshotEntityType.values()) {
			actualCounts.put(type, 0L);
		}
		EnumMap<CoreSnapshotEntityType, byte[]> previousAddresses =
				new EnumMap<>(CoreSnapshotEntityType.class);

		for (CoreSnapshotEntityChunkDescriptor descriptor : descriptors) {
			try (InputStream opened = Objects.requireNonNull(
					chunkSource.open(descriptor), "Entity chunk source returned null");
					VerifiedInputStream verified = CoreSnapshotCompression.openVerifiedZstd(
							opened,
							descriptor.compressedByteCount(), descriptor.compressedContentHash(),
							descriptor.uncompressedByteCount(), descriptor.uncompressedContentHash());
					CoreSnapshotEntityChunkCodec.Reader reader =
							CoreSnapshotEntityChunkCodec.open(verified, descriptor)) {
				while (reader.hasNext()) {
					CoreSnapshotEntityEntry entry = reader.next();
					assertStrictAddressOrder(previousAddresses, descriptor.entityType(), entry.address().toArray());
					Bytes trieValue = tries.get(descriptor.entityType()).get(entry.address())
							.orElseThrow(() -> failure("Entity sidecar address is absent from checkpoint trie: "
									+ entry.address()));
					if (!trieValue.equals(entry.canonicalState())) {
						throw failure("Entity sidecar state differs from checkpoint trie for " + entry.address());
					}
					actualCounts.merge(descriptor.entityType(), 1L, Math::addExact);
				}
				reader.finish();
				verified.finish();
				if (!verified.isVerified()) {
					throw failure("Entity chunk did not reach verified EOF: " + descriptor.index());
				}
			} catch (SnapshotVerificationException e) {
				throw e;
			} catch (Exception e) {
				throw failure("Cannot verify entity chunk " + descriptor.index(), e);
			}
		}

		for (CoreSnapshotEntityType type : CoreSnapshotEntityType.values()) {
			long trieLeafCount = countLeaves(tries.get(type));
			if (trieLeafCount != actualCounts.get(type)) {
				throw failure("Entity sidecar is incomplete for " + type
						+ ": sidecar=" + actualCounts.get(type) + ", trie=" + trieLeafCount);
			}
		}
		if (actualCounts.values().stream().mapToLong(Long::longValue).sum() != declared.entryCount()) {
			throw failure("Entity sidecar entry totals do not match descriptors");
		}
		return new VerificationResult(
				Map.copyOf(actualCounts), declared.entryCount(),
				declared.compressedBytes(), declared.uncompressedBytes());
	}

	private DescriptorTotals verifyDescriptors(List<CoreSnapshotEntityChunkDescriptor> descriptors) {
		if (descriptors.size() > MAX_CHUNK_COUNT) {
			throw failure("Entity sidecar exceeds its chunk count limit");
		}
		CoreSnapshotEntityType previousType = null;
		long entries = 0;
		long compressedBytes = 0;
		long uncompressedBytes = 0;
		for (int index = 0; index < descriptors.size(); index++) {
			CoreSnapshotEntityChunkDescriptor descriptor = descriptors.get(index);
			if (descriptor.index() != index
					|| descriptor.entryCount() < 0 || descriptor.entryCount() > MAX_ENTRIES_PER_CHUNK
					|| descriptor.compressedByteCount() <= 0
					|| descriptor.compressedByteCount() > MAX_COMPRESSED_CHUNK_BYTES
					|| descriptor.uncompressedByteCount() < CoreSnapshotEntityChunkCodec.HEADER_BYTES
					|| descriptor.uncompressedByteCount() > MAX_UNCOMPRESSED_CHUNK_BYTES) {
				throw failure("Invalid entity chunk descriptor: " + descriptor.index());
			}
			if (previousType != null && descriptor.entityType().ordinal() < previousType.ordinal()) {
				throw failure("Entity chunks must be ordered by entity type");
			}
			previousType = descriptor.entityType();
			entries = Math.addExact(entries, descriptor.entryCount());
			compressedBytes = Math.addExact(compressedBytes, descriptor.compressedByteCount());
			uncompressedBytes = Math.addExact(uncompressedBytes, descriptor.uncompressedByteCount());
			if (entries > MAX_TOTAL_ENTRIES || uncompressedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
				throw failure("Entity sidecar exceeds total limits");
			}
		}
		return new DescriptorTotals(entries, compressedBytes, uncompressedBytes);
	}

	private Map<CoreSnapshotEntityType, MerkleTrie<Bytes, Bytes>> entityTries(
			Bytes32 stateRoot, NodeLoader nodeLoader) {
		StoredNodeFactory<Bytes> mainFactory = new StoredNodeFactory<>(nodeLoader, value -> value, value -> value);
		MerkleTrie<Bytes, Bytes> mainTrie = new StoredMerklePatriciaTrie<>(mainFactory, stateRoot);
		EnumMap<CoreSnapshotEntityType, MerkleTrie<Bytes, Bytes>> result =
				new EnumMap<>(CoreSnapshotEntityType.class);
		for (CoreSnapshotEntityType type : CoreSnapshotEntityType.values()) {
			Bytes32 root = mainTrie.get(type.worldStateKey())
					.map(Bytes32::wrap)
					.orElse(MerkleTrie.EMPTY_TRIE_NODE_HASH);
			StoredNodeFactory<Bytes> factory = new StoredNodeFactory<>(nodeLoader, value -> value, value -> value);
			result.put(type, new StoredMerklePatriciaTrie<>(factory, root));
		}
		return result;
	}

	private void assertStrictAddressOrder(
			Map<CoreSnapshotEntityType, byte[]> previousAddresses,
			CoreSnapshotEntityType type,
			byte[] address) {
		byte[] previous = previousAddresses.put(type, address.clone());
		if (previous != null && Arrays.compareUnsigned(previous, address) >= 0) {
			throw failure("Entity sidecar addresses must be unique and strictly ordered for " + type);
		}
	}

	private long countLeaves(MerkleTrie<Bytes, Bytes> trie) {
		long[] count = { 0L };
		trie.visitAll(node -> {
			if (node.getValue().isPresent()) {
				count[0] = Math.addExact(count[0], 1L);
			}
		});
		return count[0];
	}

	private SnapshotVerificationException failure(String message) {
		return new SnapshotVerificationException(message);
	}

	private SnapshotVerificationException failure(String message, Throwable cause) {
		return new SnapshotVerificationException(message, cause);
	}

	private record DescriptorTotals(long entryCount, long compressedBytes, long uncompressedBytes) {
	}

	public record VerificationResult(
			Map<CoreSnapshotEntityType, Long> entryCounts,
			long totalEntries,
			long compressedBytes,
			long uncompressedBytes) {

		public VerificationResult {
			entryCounts = Map.copyOf(entryCounts);
		}
	}
}
