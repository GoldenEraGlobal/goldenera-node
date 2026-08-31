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

import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_CHUNK_COUNT;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_HEADER_COUNT;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_IN_MEMORY_BUNDLE_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_IN_MEMORY_BUNDLE_NODES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_NODES_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_NODE_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_TOTAL_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_TOTAL_NODES;
import static global.goldenera.node.core.sync.snapshot.SnapshotVerificationException.Code.UNVERIFIED_CUMULATIVE_WORK;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.NodeLoader;
import global.goldenera.merkletrie.patricia.StoredMerklePatriciaTrie;
import global.goldenera.merkletrie.patricia.StoredNodeFactory;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreArchiveHistory;

/**
 * Offline-only verifier. The production entrypoint streams nodes into an
 * isolated temporary RocksDB and never opens the production database or updates
 * canonical chain state.
 */
public final class CheckpointSnapshotVerifier {

	private static final List<Bytes> WORLD_STATE_SUBTRIE_KEYS = List.of(
			WorldStateFactory.KEY_BALANCE,
			WorldStateFactory.KEY_NONCE,
			WorldStateFactory.KEY_AUTHORITY,
			WorldStateFactory.KEY_VALIDATOR,
			WorldStateFactory.KEY_ADDRESS_ALIAS,
			WorldStateFactory.KEY_BIP_STATE,
			WorldStateFactory.KEY_NETWORK_PARAMS,
			WorldStateFactory.KEY_MINING_WINDOW,
			WorldStateFactory.KEY_MINING_REWARD_MATURITY,
			WorldStateFactory.KEY_TOKEN);

	private final SnapshotAnchorPolicy anchorPolicy;
	private final StoredChainIdentity expectedChainIdentity;
	private final long randomXEpochLength;
	private final CumulativeWorkAnchorProvider cumulativeWorkAnchorProvider;
	private final SnapshotDiskSpaceBudget diskSpaceBudget;

	public CheckpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			StoredChainIdentity expectedChainIdentity,
			long randomXEpochLength) {
		this(checkpointRegistry, expectedChainIdentity, randomXEpochLength,
				(height, hash) -> Optional.empty(), SnapshotDiskSpaceBudget.system(),
				new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	public CheckpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			StoredChainIdentity expectedChainIdentity,
			long randomXEpochLength,
			SnapshotAnchorPolicy anchorPolicy) {
		this(checkpointRegistry, expectedChainIdentity, randomXEpochLength,
				(height, hash) -> Optional.empty(), SnapshotDiskSpaceBudget.system(), anchorPolicy);
	}

	public CheckpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			StoredChainIdentity expectedChainIdentity,
			long randomXEpochLength,
			CumulativeWorkAnchorProvider cumulativeWorkAnchorProvider) {
		this(checkpointRegistry, expectedChainIdentity, randomXEpochLength,
				cumulativeWorkAnchorProvider, SnapshotDiskSpaceBudget.system(),
				new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	CheckpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			StoredChainIdentity expectedChainIdentity,
			long randomXEpochLength,
			CumulativeWorkAnchorProvider cumulativeWorkAnchorProvider,
			SnapshotDiskSpaceBudget diskSpaceBudget) {
		this(checkpointRegistry, expectedChainIdentity, randomXEpochLength, cumulativeWorkAnchorProvider,
				diskSpaceBudget, new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	CheckpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			StoredChainIdentity expectedChainIdentity,
			long randomXEpochLength,
			CumulativeWorkAnchorProvider cumulativeWorkAnchorProvider,
			SnapshotDiskSpaceBudget diskSpaceBudget,
			SnapshotAnchorPolicy anchorPolicy) {
		Objects.requireNonNull(checkpointRegistry, "checkpointRegistry");
		this.expectedChainIdentity = Objects.requireNonNull(expectedChainIdentity, "expectedChainIdentity");
		if (randomXEpochLength <= 0) {
			throw new IllegalArgumentException("RandomX epoch length must be positive");
		}
		this.randomXEpochLength = randomXEpochLength;
		this.cumulativeWorkAnchorProvider = Objects.requireNonNull(
				cumulativeWorkAnchorProvider, "cumulativeWorkAnchorProvider");
		this.diskSpaceBudget = Objects.requireNonNull(diskSpaceBudget, "diskSpaceBudget");
		this.anchorPolicy = Objects.requireNonNull(anchorPolicy, "anchorPolicy");
	}

	public VerificationResult verify(CheckpointSnapshotBundle bundle) {
		Objects.requireNonNull(bundle, "bundle");
		long bundleBytes = 0;
		long bundleNodes = 0;
		for (SnapshotChunk chunk : bundle.chunks()) {
			bundleNodes = Math.addExact(bundleNodes, chunk.nodes().size());
			for (SnapshotNode node : chunk.nodes()) {
				bundleBytes = Math.addExact(bundleBytes, node.content().size());
				if (bundleBytes > MAX_IN_MEMORY_BUNDLE_BYTES || bundleNodes > MAX_IN_MEMORY_BUNDLE_NODES) {
					throw failure("In-memory bundle verification is limited to " + MAX_IN_MEMORY_BUNDLE_BYTES
							+ " bytes and " + MAX_IN_MEMORY_BUNDLE_NODES
							+ " nodes; use the streaming SnapshotChunkSource entrypoint");
				}
			}
		}
		Map<Integer, SnapshotChunk> chunksByIndex = new HashMap<>();
		for (SnapshotChunk chunk : bundle.chunks()) {
			if (chunksByIndex.putIfAbsent(chunk.index(), chunk) != null) {
				throw failure("Snapshot contains an undeclared or duplicate chunk: " + chunk.index());
			}
		}
		if (chunksByIndex.size() != bundle.manifest().chunks().size()) {
			throw failure("Snapshot chunk set does not match manifest declarations");
		}
		Set<Integer> declaredIndexes = bundle.manifest().chunks().stream()
				.map(SnapshotChunkDescriptor::index).collect(java.util.stream.Collectors.toSet());
		if (!declaredIndexes.equals(chunksByIndex.keySet())) {
			throw failure("Snapshot contains an undeclared chunk or is missing a declared chunk");
		}
		return verify(bundle.manifest(), descriptor -> {
			SnapshotChunk chunk = chunksByIndex.get(descriptor.index());
			if (chunk == null) {
				throw failure("Snapshot is missing declared chunk: " + descriptor.id());
			}
			return inMemoryNodeSource(chunk.nodes());
		}, cumulativeWorkAnchorProvider, CheckpointStateSupplementVerifier.none());
	}

	public VerificationResult verify(CheckpointSnapshotManifest manifest, SnapshotChunkSource chunkSource) {
		return verify(manifest, chunkSource, cumulativeWorkAnchorProvider,
				CheckpointStateSupplementVerifier.none());
	}

	/**
	 * Performs the transport preflight which is safe before any potentially large
	 * chunk is downloaded. It verifies the exact configured chain identity and
	 * hardcoded checkpoint, canonical header hashes/links/state root, and internal
	 * cumulative-work arithmetic. The segment parent work is intentionally only a
	 * manifest-local anchor here; activation still requires {@link #verify} with a
	 * locally trusted anchor or {@link #verifyWithFullHistoryAnchor}.
	 */
	public void verifyManifestMetadataForTransport(CheckpointSnapshotManifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		verifyManifestIdentity(manifest);
		SnapshotHeaderSegment segment = manifest.headerSegment();
		if (segment == null) {
			throw failure("Snapshot header segment is required");
		}
		long requiredSeedHeight = requiredRandomXSeedHeight(
				Math.addExact(manifest.checkpointHeight(), 1L));
		CumulativeWorkAnchorProvider manifestLocalAnchor = (height, hash) ->
				requiredSeedHeight > 0 && height == requiredSeedHeight - 1
						&& Objects.equals(hash, segment.parentHash())
						? Optional.of(segment.parentCumulativeDifficulty()) : Optional.empty();
		verifyHeaderSegment(manifest, manifestLocalAnchor);
		verifyChunkDescriptors(manifest.chunks());
	}

	/**
	 * Fresh-node entrypoint used only after a complete archive history has been
	 * streamed and verified. Manifest identity and the exact hardcoded checkpoint
	 * are still checked here before the archive-derived work anchor is trusted.
	 */
	public VerificationResult verifyWithFullHistoryAnchor(
			CheckpointSnapshotManifest manifest,
			SnapshotChunkSource chunkSource,
			VerifiedCoreArchiveHistory fullHistory) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(chunkSource, "chunkSource");
		Objects.requireNonNull(fullHistory, "fullHistory");
		if (manifest.checkpointHeight() != fullHistory.checkpointHeight()
				|| !manifest.checkpointHash().equals(fullHistory.checkpointHash())
				|| !manifest.checkpointCumulativeDifficulty().equals(
						fullHistory.checkpointCumulativeDifficulty())) {
			throw failure("Full archive history does not match the state checkpoint");
		}
		return verify(manifest, chunkSource, fullHistory::findCumulativeDifficulty,
				CheckpointStateSupplementVerifier.none());
	}

	public VerificationResult verifyWithFullHistoryAnchor(
			CheckpointSnapshotManifest manifest,
			SnapshotChunkSource chunkSource,
			VerifiedCoreArchiveHistory fullHistory,
			CheckpointStateSupplementVerifier supplementVerifier) {
		Objects.requireNonNull(supplementVerifier, "supplementVerifier");
		if (manifest.checkpointHeight() != fullHistory.checkpointHeight()
				|| !manifest.checkpointHash().equals(fullHistory.checkpointHash())
				|| !manifest.checkpointCumulativeDifficulty().equals(
						fullHistory.checkpointCumulativeDifficulty())) {
			throw failure("Full archive history does not match the state checkpoint");
		}
		return verify(manifest, chunkSource, fullHistory::findCumulativeDifficulty, supplementVerifier);
	}

	private VerificationResult verify(
			CheckpointSnapshotManifest manifest,
			SnapshotChunkSource chunkSource,
			CumulativeWorkAnchorProvider workAnchorProvider,
			CheckpointStateSupplementVerifier supplementVerifier) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(chunkSource, "chunkSource");
		verifyManifestIdentity(manifest);
		verifyHeaderSegment(manifest, workAnchorProvider);
		verifyChunkDescriptors(manifest.chunks());
		diskSpaceBudget.requireVerification(manifest, null);
		try (TemporarySnapshotNodeStore stagingStore = TemporarySnapshotNodeStore.create()) {
			stageChunks(manifest.chunks(), chunkSource, stagingStore);
			verifyWorldState(manifest.checkpointStateRoot(), stagingStore);
			supplementVerifier.verify(manifest.checkpointStateRoot(), stagingStore);
			if (stagingStore.hasUnvisitedNodes()) {
				throw failure("Snapshot contains unreachable trie node(s)");
			}
			return new VerificationResult(
					manifest.checkpointHeight(), manifest.checkpointHash(), manifest.checkpointStateRoot(),
					Math.toIntExact(stagingStore.nodeCount()), manifest.chunks().size(),
					CheckpointSnapshotManifestCodec.signingHash(manifest));
		}
	}

	private void verifyManifestIdentity(CheckpointSnapshotManifest manifest) {
		if (!SnapshotFormatCompatibility.supportsState(manifest.formatVersion())) {
			throw failure("Unsupported snapshot format version: " + manifest.formatVersion());
		}
		if (manifest.networkCode() != expectedChainIdentity.carrierNetworkCode()
				|| manifest.chainIdentity().carrierNetworkCode() != manifest.networkCode()
				|| !manifest.chainIdentity().equals(expectedChainIdentity)) {
			throw failure("Snapshot chain identity/network does not match this node");
		}
		if (manifest.checkpointHeight() < 0 || manifest.checkpointCumulativeDifficulty().signum() <= 0) {
			throw failure("Checkpoint height and cumulative difficulty must be positive");
		}
		anchorPolicy.verify(manifest.checkpointHeight(), manifest.checkpointHash(), manifest.chainIdentity());
	}

	private void verifyHeaderSegment(
			CheckpointSnapshotManifest manifest,
			CumulativeWorkAnchorProvider workAnchorProvider) {
		SnapshotHeaderSegment segment = manifest.headerSegment();
		List<SnapshotHeader> headers = segment.headers();
		if (headers.isEmpty() || headers.size() > MAX_HEADER_COUNT) {
			throw failure("Header segment must contain 1.." + MAX_HEADER_COUNT + " headers");
		}
		if (manifest.checkpointHeight() == Long.MAX_VALUE) {
			throw failure("Checkpoint height is too large");
		}
		long requiredSeedHeight = requiredRandomXSeedHeight(manifest.checkpointHeight() + 1);
		if (headers.getFirst().header().getHeight() != requiredSeedHeight
				|| headers.getLast().header().getHeight() != manifest.checkpointHeight()) {
			throw failure("Header segment does not cover the required RandomX seed window");
		}
		if (segment.parentCumulativeDifficulty().signum() < 0) {
			throw failure("Header segment parent cumulative difficulty cannot be negative");
		}
		verifyCumulativeWorkAnchor(requiredSeedHeight, segment, workAnchorProvider);

		Hash expectedParentHash = segment.parentHash();
		BigInteger cumulativeDifficulty = segment.parentCumulativeDifficulty();
		long expectedHeight = requiredSeedHeight;
		for (SnapshotHeader snapshotHeader : headers) {
			BlockHeader header = snapshotHeader.header();
			if (header.getHeight() != expectedHeight++) {
				throw failure("Header segment is not height-contiguous");
			}
			if (header.getPreviousHash() == null || !header.getPreviousHash().equals(expectedParentHash)) {
				throw failure("Header segment has a broken previous-hash link at height " + header.getHeight());
			}
			if (header.getDifficulty() == null || header.getDifficulty().signum() <= 0) {
				throw failure("Header has non-positive difficulty at height " + header.getHeight());
			}
			Hash calculatedHash;
			try {
				calculatedHash = header.getHash();
			} catch (RuntimeException e) {
				throw failure("Header cannot be canonically hashed at height " + header.getHeight(), e);
			}
			if (!calculatedHash.equals(snapshotHeader.declaredHash())) {
				throw failure("Declared header hash mismatch at height " + header.getHeight());
			}
			cumulativeDifficulty = cumulativeDifficulty.add(header.getDifficulty());
			if (!cumulativeDifficulty.equals(snapshotHeader.cumulativeDifficulty())) {
				throw failure("Header cumulative difficulty mismatch at height " + header.getHeight());
			}
			expectedParentHash = calculatedHash;
		}

		SnapshotHeader checkpoint = headers.getLast();
		if (!checkpoint.declaredHash().equals(manifest.checkpointHash())
				|| !checkpoint.header().getStateRootHash().equals(manifest.checkpointStateRoot())
				|| !checkpoint.cumulativeDifficulty().equals(manifest.checkpointCumulativeDifficulty())) {
			throw failure("Checkpoint header does not match manifest hash/state root/cumulative difficulty");
		}
	}

	private void verifyCumulativeWorkAnchor(
			long segmentStartHeight,
			SnapshotHeaderSegment segment,
			CumulativeWorkAnchorProvider workAnchorProvider) {
		if (segmentStartHeight == 0) {
			if (!segment.parentHash().equals(Hash.ZERO)
					|| !segment.parentCumulativeDifficulty().equals(BigInteger.ZERO)) {
				throw unverifiedWork("Genesis-rooted header segment must use zero parent hash and cumulative work");
			}
			return;
		}
		long parentHeight = segmentStartHeight - 1;
		Optional<BigInteger> trustedDifficulty = workAnchorProvider.findCumulativeDifficulty(
				parentHeight, segment.parentHash());
		if (trustedDifficulty.isEmpty()
				|| !trustedDifficulty.orElseThrow().equals(segment.parentCumulativeDifficulty())) {
			throw unverifiedWork("No locally verified cumulative-work anchor for snapshot segment parent at height "
					+ parentHeight);
		}
	}

	private long requiredRandomXSeedHeight(long firstPostCheckpointHeight) {
		long epoch = firstPostCheckpointHeight / randomXEpochLength;
		return epoch == 0 ? 0 : Math.multiplyExact(epoch - 1, randomXEpochLength);
	}

	private void verifyChunkDescriptors(List<SnapshotChunkDescriptor> descriptors) {
		if (descriptors.size() > MAX_CHUNK_COUNT) {
			throw failure("Snapshot exceeds chunk count limit");
		}
		Map<Integer, SnapshotChunkDescriptor> declared = new HashMap<>();
		Set<String> chunkIds = new HashSet<>();
		for (int index = 0; index < descriptors.size(); index++) {
			SnapshotChunkDescriptor descriptor = descriptors.get(index);
			if (descriptor.index() != index || declared.put(descriptor.index(), descriptor) != null
					|| !chunkIds.add(descriptor.id())) {
				throw failure("Chunk descriptors must have unique contiguous indexes");
			}
			if (descriptor.nodeCount() < 0 || descriptor.nodeCount() > MAX_NODES_PER_CHUNK
					|| descriptor.byteCount() < 0 || descriptor.byteCount() > MAX_CHUNK_BYTES) {
				throw failure("Declared chunk exceeds node or byte limits: " + descriptor.id());
			}
		}
	}

	private void stageChunks(
			List<SnapshotChunkDescriptor> descriptors,
			SnapshotChunkSource chunkSource,
			TemporarySnapshotNodeStore stagingStore) {
		long totalBytes = 0;
		long totalNodes = 0;
		for (SnapshotChunkDescriptor descriptor : descriptors) {
			Digest256 digest = new Digest256();
			updateInt(digest, descriptor.index());
			long chunkBytes = 0;
			int chunkNodes = 0;
			try (SnapshotNodeSource nodes = chunkSource.open(descriptor)) {
				while (nodes.hasNext()) {
					SnapshotNode node = Objects.requireNonNull(nodes.next(), "snapshot node");
					chunkNodes++;
					if (chunkNodes > descriptor.nodeCount() || chunkNodes > MAX_NODES_PER_CHUNK) {
						throw failure("Chunk contains more nodes than declared: " + descriptor.id());
					}
					if (node.content().isEmpty() || node.content().size() > MAX_NODE_BYTES) {
						throw failure("Trie node exceeds size limits");
					}
					chunkBytes = Math.addExact(chunkBytes, node.content().size());
					if (chunkBytes > descriptor.byteCount() || chunkBytes > MAX_CHUNK_BYTES) {
						throw failure("Chunk contains more bytes than declared: " + descriptor.id());
					}
					if (!Hash.hash(node.content()).equals(node.key())) {
						throw failure("Trie node key does not equal Hash(content): " + node.key());
					}
					digest.update(node.key().toArray());
					updateInt(digest, node.content().size());
					digest.update(node.content().toArray());
					stagingStore.put(node);
				}
			} catch (SnapshotVerificationException e) {
				throw e;
			} catch (Exception e) {
				throw failure("Cannot read declared chunk " + descriptor.id(), e);
			}
			if (chunkNodes != descriptor.nodeCount() || chunkBytes != descriptor.byteCount()
					|| !Hash.wrap(digest.digest()).equals(descriptor.contentHash())) {
				throw failure("Chunk integrity mismatch: " + descriptor.id());
			}
			totalBytes = Math.addExact(totalBytes, chunkBytes);
			totalNodes = Math.addExact(totalNodes, chunkNodes);
			if (totalBytes > MAX_TOTAL_BYTES || totalNodes > MAX_TOTAL_NODES) {
				throw failure("Snapshot exceeds total node or byte limits");
			}
		}
	}

	public static Hash chunkContentHash(SnapshotChunk chunk) {
		Digest256 digest = new Digest256();
		updateInt(digest, chunk.index());
		for (SnapshotNode node : chunk.nodes()) {
			digest.update(node.key().toArray());
			updateInt(digest, node.content().size());
			digest.update(node.content().toArray());
		}
		return Hash.wrap(digest.digest());
	}

	private static void updateInt(Digest256 digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private void verifyWorldState(Hash stateRoot, NodeLoader loader) {
		try {
			MerkleTrie<Bytes, Bytes> mainTrie = trie(stateRoot, loader);
			mainTrie.visitAll(ignored -> { });
			for (Bytes subTrieKey : WORLD_STATE_SUBTRIE_KEYS) {
				Optional<Bytes> encodedRoot = mainTrie.get(subTrieKey);
				if (encodedRoot.isEmpty()) {
					continue;
				}
				if (encodedRoot.orElseThrow().size() != Hash.SIZE) {
					throw failure("WorldState subtrie root has invalid length for key " + subTrieKey);
				}
				Hash subTrieRoot = Hash.wrap(Bytes32.wrap(encodedRoot.orElseThrow()));
				trie(subTrieRoot, loader).visitAll(ignored -> { });
			}
		} catch (SnapshotVerificationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure("WorldState trie traversal failed: " + e.getMessage(), e);
		}
	}

	private MerkleTrie<Bytes, Bytes> trie(Hash root, NodeLoader loader) {
		StoredNodeFactory<Bytes> factory = new StoredNodeFactory<>(loader, value -> value, value -> value);
		return new StoredMerklePatriciaTrie<>(factory, root);
	}

	private SnapshotVerificationException failure(String message) {
		return new SnapshotVerificationException(message);
	}

	private SnapshotVerificationException failure(String message, Throwable cause) {
		return new SnapshotVerificationException(message, cause);
	}

	private SnapshotVerificationException unverifiedWork(String message) {
		return new SnapshotVerificationException(UNVERIFIED_CUMULATIVE_WORK, message);
	}

	@FunctionalInterface
	public interface CumulativeWorkAnchorProvider {
		Optional<BigInteger> findCumulativeDifficulty(long height, Hash hash);
	}

	public record VerificationResult(
			long checkpointHeight,
			Hash checkpointHash,
			Hash stateRoot,
			int nodeCount,
			int chunkCount,
			Hash manifestSigningHash) {
	}

	private SnapshotNodeSource inMemoryNodeSource(List<SnapshotNode> nodes) {
		Iterator<SnapshotNode> iterator = nodes.iterator();
		return new SnapshotNodeSource() {
			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}

			@Override
			public SnapshotNode next() {
				return iterator.next();
			}

			@Override
			public void close() {
			}
		};
	}
}
