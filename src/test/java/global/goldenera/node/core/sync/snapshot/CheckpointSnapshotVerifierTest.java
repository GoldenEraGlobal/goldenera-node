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

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.patricia.SimpleMerklePatriciaTrie;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;

class CheckpointSnapshotVerifierTest {

	private static final StoredChainIdentity CHAIN = new StoredChainIdentity(
			StoredChainIdentity.CURRENT_FORMAT_VERSION,
			1,
			"testnet",
			"0x" + "12".repeat(32),
			null);

	@Test
	void verifiesMinimalMainTrieAndWorldStateSubtrieOffline() {
		Fixture fixture = fixture();

		CheckpointSnapshotVerifier.VerificationResult result = fixture.verifier.verify(fixture.bundle);

		assertThat(result.checkpointHeight()).isEqualTo(2);
		assertThat(result.stateRoot()).isEqualTo(fixture.stateRoot);
		assertThat(result.nodeCount()).isEqualTo(2);
		assertThat(result.chunkCount()).isOne();
		assertThat(result.manifestSigningHash())
				.isEqualTo(CheckpointSnapshotManifestCodec.signingHash(fixture.bundle.manifest()));
		var canonicalManifest = CheckpointSnapshotManifestCodec.canonicalBytes(fixture.bundle.manifest());
		CheckpointSnapshotManifest decodedManifest =
				CheckpointSnapshotManifestCodec.decodeCanonicalBytes(canonicalManifest);
		assertThat(CheckpointSnapshotManifestCodec.canonicalBytes(decodedManifest)).isEqualTo(canonicalManifest);
		assertThat(CheckpointSnapshotManifestCodec.signingHash(decodedManifest))
				.isEqualTo(result.manifestSigningHash());
	}

	@Test
	void rejectsCorruptNodeEvenWhenChunkDescriptorMatchesCorruptPayload() {
		Fixture fixture = fixture();
		List<SnapshotNode> nodes = new ArrayList<>(fixture.bundle.chunks().getFirst().nodes());
		SnapshotNode original = nodes.getLast();
		byte[] corruptBytes = original.content().toArray();
		corruptBytes[corruptBytes.length - 1] ^= 1;
		nodes.set(nodes.size() - 1, new SnapshotNode(original.key(), Bytes.wrap(corruptBytes)));
		CheckpointSnapshotBundle corrupt = withNodes(fixture.bundle, nodes);

		assertThatThrownBy(() -> fixture.verifier.verify(corrupt))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("key does not equal Hash(content)");
	}

	@Test
	void rejectsMissingSubtrieNodeDuringFullTraversal() {
		Fixture fixture = fixture();
		List<SnapshotNode> nodes = fixture.bundle.chunks().getFirst().nodes().stream()
				.filter(node -> !node.key().equals(fixture.balanceRoot))
				.toList();
		CheckpointSnapshotBundle missing = withNodes(fixture.bundle, nodes);

		assertThatThrownBy(() -> fixture.verifier.verify(missing))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("WorldState trie traversal failed")
				.hasMessageContaining("Unable to load trie node");
	}

	@Test
	void rejectsWrongChainIdentityAndNetwork() {
		Fixture fixture = fixture();
		StoredChainIdentity otherChain = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 0, "mainnet", "0x" + "34".repeat(32), null);
		CheckpointSnapshotManifest source = fixture.bundle.manifest();
		CheckpointSnapshotManifest wrong = new CheckpointSnapshotManifest(
				source.formatVersion(), 0, otherChain, source.checkpointHeight(), source.checkpointHash(),
				source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(), source.headerSegment(),
				source.chunks());

		assertThatThrownBy(() -> fixture.verifier.verify(new CheckpointSnapshotBundle(wrong, fixture.bundle.chunks())))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("chain identity/network");
	}

	@Test
	void requiresExactHardcodedCheckpoint() {
		Fixture fixture = fixture();
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(2)).thenReturn(true);
		when(registry.verifyCheckpoint(2, fixture.bundle.manifest().checkpointHash())).thenReturn(false);
		CheckpointSnapshotVerifier verifier = new CheckpointSnapshotVerifier(registry, CHAIN, 2);

		assertThatThrownBy(() -> verifier.verify(fixture.bundle))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("exact hardcoded checkpoint");
	}

	@Test
	void rejectsHeaderSegmentWithoutRequiredRandomXSeedHistory() {
		Fixture fixture = fixture();
		CheckpointSnapshotManifest source = fixture.bundle.manifest();
		List<SnapshotHeader> insufficient = source.headerSegment().headers().subList(1, 3);
		SnapshotHeaderSegment segment = new SnapshotHeaderSegment(
				source.headerSegment().headers().getFirst().declaredHash(), BigInteger.ONE, insufficient);
		CheckpointSnapshotManifest wrong = new CheckpointSnapshotManifest(
				source.formatVersion(), source.networkCode(), source.chainIdentity(), source.checkpointHeight(),
				source.checkpointHash(), source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(), segment,
				source.chunks());

		assertThatThrownBy(() -> fixture.verifier.verify(new CheckpointSnapshotBundle(wrong, fixture.bundle.chunks())))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("RandomX seed window");
	}

	@Test
	void failsClosedWhenNonGenesisSegmentHasNoLocallyVerifiedCumulativeWorkAnchor() {
		Fixture fixture = fixture();
		CheckpointSnapshotManifest source = fixture.bundle.manifest();
		SnapshotHeader checkpoint = source.headerSegment().headers().getLast();
		SnapshotHeader parent = source.headerSegment().headers().get(1);
		SnapshotHeaderSegment segment = new SnapshotHeaderSegment(
				parent.declaredHash(), parent.cumulativeDifficulty(), List.of(checkpoint));
		CheckpointSnapshotManifest shortManifest = new CheckpointSnapshotManifest(
				source.formatVersion(), source.networkCode(), source.chainIdentity(), source.checkpointHeight(),
				source.checkpointHash(), source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(), segment,
				source.chunks());
		CheckpointSnapshotVerifier verifier = new CheckpointSnapshotVerifier(
				checkpointRegistryFor(source), CHAIN, 1);

		assertThatThrownBy(() -> verifier.verify(new CheckpointSnapshotBundle(shortManifest, fixture.bundle.chunks())))
				.isInstanceOfSatisfying(SnapshotVerificationException.class, failure ->
						assertThat(failure.code()).isEqualTo(
								SnapshotVerificationException.Code.UNVERIFIED_CUMULATIVE_WORK));
	}

	@Test
	void streamingEntrypointAcceptsNonGenesisSegmentOnlyWithExactLocalWorkAnchor() {
		Fixture fixture = fixture();
		CheckpointSnapshotManifest source = fixture.bundle.manifest();
		SnapshotHeader checkpoint = source.headerSegment().headers().getLast();
		SnapshotHeader parent = source.headerSegment().headers().get(1);
		SnapshotHeaderSegment segment = new SnapshotHeaderSegment(
				parent.declaredHash(), parent.cumulativeDifficulty(), List.of(checkpoint));
		CheckpointSnapshotManifest shortManifest = new CheckpointSnapshotManifest(
				source.formatVersion(), source.networkCode(), source.chainIdentity(), source.checkpointHeight(),
				source.checkpointHash(), source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(), segment,
				source.chunks());
		CheckpointSnapshotVerifier verifier = new CheckpointSnapshotVerifier(
				checkpointRegistryFor(source), CHAIN, 1,
				(height, hash) -> height == 1 && hash.equals(parent.declaredHash())
						? Optional.of(parent.cumulativeDifficulty()) : Optional.empty());
		List<SnapshotNode> nodes = fixture.bundle.chunks().getFirst().nodes();

		CheckpointSnapshotVerifier.VerificationResult result = verifier.verify(
				shortManifest, ignored -> nodeSource(nodes));

		assertThat(result.stateRoot()).isEqualTo(fixture.stateRoot);
	}

	@Test
	void fullArchiveSuppliesFreshNodeWorkAnchorWithoutLocalChainHistory() {
		Fixture fixture = fixture();
		CheckpointSnapshotManifest original = fixture.bundle.manifest();
		List<SnapshotHeader> completeHeaders = original.headerSegment().headers();
		SnapshotHeader parent = completeHeaders.get(1);
		SnapshotHeader checkpoint = completeHeaders.getLast();
		StoredChainIdentity archiveChain = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, CHAIN.carrierNetworkCode(), CHAIN.chainId(),
				completeHeaders.getFirst().declaredHash().toHexString(), null);
		CheckpointSnapshotManifest shortStateManifest = new CheckpointSnapshotManifest(
				original.formatVersion(), original.networkCode(), archiveChain, original.checkpointHeight(),
				original.checkpointHash(), original.checkpointStateRoot(), original.checkpointCumulativeDifficulty(),
				new SnapshotHeaderSegment(
						parent.declaredHash(), parent.cumulativeDifficulty(), List.of(checkpoint)),
				original.chunks());

		List<StoredBlock> storedBlocks = completeHeaders.stream().map(snapshotHeader -> {
			Block block = BlockImpl.builder().header(snapshotHeader.header()).txs(List.of()).build();
			return StoredBlock.builder()
					.block(block)
					.cumulativeDifficulty(snapshotHeader.cumulativeDifficulty())
					.receivedAt(snapshotHeader.header().getTimestamp())
					.receivedFrom(Address.ZERO)
					.connectedSource(snapshotHeader.header().getHeight() == 0
							? ConnectedSource.GENESIS : ConnectedSource.SYNC)
					.identity(snapshotHeader.header().getIdentity())
					.computeIndexes()
					.build();
		}).toList();
		Bytes blockChunk = CoreSnapshotBlockChunkCodec.encodeChunk(0, storedBlocks);
		CoreSnapshotBlockChunkDescriptor blockDescriptor = new CoreSnapshotBlockChunkDescriptor(
				0, 0, original.checkpointHeight(), storedBlocks.size(), blockChunk.size(), Hash.hash(blockChunk));
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				1, CheckpointSnapshotManifestCodec.signingHash(shortStateManifest), List.of(blockDescriptor));
		CheckpointSnapshotVerifier freshStateVerifier = new CheckpointSnapshotVerifier(
				checkpointRegistryFor(shortStateManifest), archiveChain, 1);
		assertThatThrownBy(() -> freshStateVerifier.verify(
				shortStateManifest, ignored -> nodeSource(fixture.bundle.chunks().getFirst().nodes())))
				.isInstanceOfSatisfying(SnapshotVerificationException.class, failure ->
						assertThat(failure.code()).isEqualTo(
								SnapshotVerificationException.Code.UNVERIFIED_CUMULATIVE_WORK));

		VerifiedCoreSnapshotArchive verified = new CoreSnapshotArchiveVerifier(freshStateVerifier).verify(
				archiveManifest,
				shortStateManifest,
				ignored -> nodeSource(fixture.bundle.chunks().getFirst().nodes()),
				ignored -> new ByteArrayInputStream(blockChunk.toArrayUnsafe()));

		assertThat(verified.activationEligible()).isTrue();
		assertThat(verified.checkpointHash()).isEqualTo(shortStateManifest.checkpointHash());
		assertThat(verified.archiveManifestSigningHash())
				.isEqualTo(CoreSnapshotArchiveManifestCodec.signingHash(archiveManifest));
	}

	@Test
	void rejectsDuplicateAndUndeclaredChunks() {
		Fixture fixture = fixture();
		SnapshotChunk chunk = fixture.bundle.chunks().getFirst();

		assertThatThrownBy(() -> fixture.verifier.verify(
				new CheckpointSnapshotBundle(fixture.bundle.manifest(), List.of(chunk, chunk))))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("duplicate chunk");

		SnapshotChunk undeclared = new SnapshotChunk(9, chunk.nodes());
		assertThatThrownBy(() -> fixture.verifier.verify(
				new CheckpointSnapshotBundle(fixture.bundle.manifest(), List.of(undeclared))))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("undeclared chunk");
	}

	@Test
	void rejectsDeclaredChunkOverConfiguredLimitBeforeReadingNodes() {
		Fixture fixture = fixture();
		CheckpointSnapshotManifest source = fixture.bundle.manifest();
		SnapshotChunkDescriptor descriptor = source.chunks().getFirst();
		SnapshotChunkDescriptor oversize = new SnapshotChunkDescriptor(
				descriptor.index(), descriptor.id(), descriptor.url(), descriptor.nodeCount(),
				CheckpointSnapshotLimits.MAX_CHUNK_BYTES + 1, descriptor.contentHash());
		CheckpointSnapshotManifest wrong = new CheckpointSnapshotManifest(
				source.formatVersion(), source.networkCode(), source.chainIdentity(), source.checkpointHeight(),
				source.checkpointHash(), source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(),
				source.headerSegment(), List.of(oversize));

		assertThatThrownBy(() -> fixture.verifier.verify(new CheckpointSnapshotBundle(wrong, fixture.bundle.chunks())))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("exceeds node or byte limits");
	}

	@Test
	void rejectsUnreachableButHashValidTrieNode() {
		Fixture fixture = fixture();
		List<SnapshotNode> nodes = new ArrayList<>(fixture.bundle.chunks().getFirst().nodes());
		Bytes unrelatedContent = Bytes.wrap(new byte[96]);
		nodes.add(new SnapshotNode(Hash.hash(unrelatedContent), unrelatedContent));

		assertThatThrownBy(() -> fixture.verifier.verify(withNodes(fixture.bundle, nodes)))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("unreachable trie node");
	}

	private Fixture fixture() {
		TrieData state = stateTrie();
		List<SnapshotHeader> headers = headers(state.mainRoot);
		SnapshotHeader checkpoint = headers.getLast();
		SnapshotChunk chunk = new SnapshotChunk(0, state.nodes);
		SnapshotChunkDescriptor descriptor = descriptor(chunk);
		CheckpointSnapshotManifest manifest = new CheckpointSnapshotManifest(
				CheckpointSnapshotLimits.FORMAT_VERSION,
				CHAIN.carrierNetworkCode(),
				CHAIN,
				2,
				checkpoint.declaredHash(),
				state.mainRoot,
				checkpoint.cumulativeDifficulty(),
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, headers),
				List.of(descriptor));
		CheckpointSnapshotBundle bundle = new CheckpointSnapshotBundle(manifest, List.of(chunk));
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(2)).thenReturn(true);
		when(registry.verifyCheckpoint(2, checkpoint.declaredHash())).thenReturn(true);
		return new Fixture(new CheckpointSnapshotVerifier(registry, CHAIN, 2), bundle,
				state.mainRoot, state.balanceRoot);
	}

	private CheckpointRegistry checkpointRegistryFor(CheckpointSnapshotManifest manifest) {
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(manifest.checkpointHeight())).thenReturn(true);
		when(registry.verifyCheckpoint(manifest.checkpointHeight(), manifest.checkpointHash())).thenReturn(true);
		return registry;
	}

	private TrieData stateTrie() {
		SimpleMerklePatriciaTrie<Bytes, Bytes> balanceTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		byte[] balanceValue = new byte[64];
		balanceValue[0] = 1;
		balanceTrie.put(Bytes.of(1), Bytes.wrap(balanceValue));
		Hash balanceRoot = Hash.wrap(balanceTrie.getRootHash());

		SimpleMerklePatriciaTrie<Bytes, Bytes> mainTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		mainTrie.put(WorldStateFactory.KEY_BALANCE, balanceRoot);
		Hash mainRoot = Hash.wrap(mainTrie.getRootHash());

		Map<Hash, Bytes> nodes = new LinkedHashMap<>();
		collectReferencedNodes(mainTrie, mainRoot, nodes);
		collectReferencedNodes(balanceTrie, balanceRoot, nodes);
		return new TrieData(mainRoot, balanceRoot,
				nodes.entrySet().stream().map(entry -> new SnapshotNode(entry.getKey(), entry.getValue())).toList());
	}

	private void collectReferencedNodes(
			MerkleTrie<Bytes, Bytes> trie, Hash root, Map<Hash, Bytes> nodes) {
		trie.visitAll(node -> {
			if (node.getHash().equals(root) || node.isReferencedByHash()) {
				nodes.put(Hash.wrap(node.getHash()), node.getEncodedBytes());
			}
		});
	}

	private List<SnapshotHeader> headers(Hash checkpointStateRoot) {
		List<SnapshotHeader> headers = new ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (long height = 0; height <= 2; height++) {
			BlockHeader header = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
					.previousHash(previous)
					.txRootHash(Hash.ZERO)
					.stateRootHash(height == 2 ? checkpointStateRoot : MerkleTrie.EMPTY_TRIE_NODE_HASH)
					.difficulty(BigInteger.ONE)
					.coinbase(Address.ZERO)
					.nonce(height)
					.signature(Signature.ZERO)
					.build();
			cumulative = cumulative.add(header.getDifficulty());
			headers.add(new SnapshotHeader(header.getHash(), header, cumulative));
			previous = header.getHash();
		}
		return List.copyOf(headers);
	}

	private CheckpointSnapshotBundle withNodes(CheckpointSnapshotBundle source, List<SnapshotNode> nodes) {
		SnapshotChunk chunk = new SnapshotChunk(0, nodes);
		CheckpointSnapshotManifest manifest = source.manifest();
		CheckpointSnapshotManifest updated = new CheckpointSnapshotManifest(
				manifest.formatVersion(), manifest.networkCode(), manifest.chainIdentity(), manifest.checkpointHeight(),
				manifest.checkpointHash(), manifest.checkpointStateRoot(), manifest.checkpointCumulativeDifficulty(),
				manifest.headerSegment(), List.of(descriptor(chunk)));
		return new CheckpointSnapshotBundle(updated, List.of(chunk));
	}

	private SnapshotChunkDescriptor descriptor(SnapshotChunk chunk) {
		long bytes = chunk.nodes().stream().mapToLong(node -> node.content().size()).sum();
		return new SnapshotChunkDescriptor(
				chunk.index(), "state-0000", "https://snapshots.example.test/state-0000.bin",
				chunk.nodes().size(), bytes, CheckpointSnapshotVerifier.chunkContentHash(chunk));
	}

	private SnapshotNodeSource nodeSource(List<SnapshotNode> nodes) {
		var iterator = nodes.iterator();
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

	private record Fixture(
			CheckpointSnapshotVerifier verifier,
			CheckpointSnapshotBundle bundle,
			Hash stateRoot,
			Hash balanceRoot) {
	}

	private record TrieData(Hash mainRoot, Hash balanceRoot, List<SnapshotNode> nodes) {
	}
}
