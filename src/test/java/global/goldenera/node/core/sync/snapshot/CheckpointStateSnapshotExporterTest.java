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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.state.impl.AccountNonceStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.Node;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.transport.BinarySnapshotNodeSource;
import global.goldenera.node.core.sync.snapshot.transport.SnapshotTransportManifest;

class CheckpointStateSnapshotExporterTest {

	private static final StoredChainIdentity CHAIN = new StoredChainIdentity(
			StoredChainIdentity.CURRENT_FORMAT_VERSION, 1, "testnet", "0x" + "12".repeat(32), null);
	private static final URI PUBLIC_ORIGIN = URI.create("https://snapshots.example.test/");

	@TempDir
	Path temporaryDirectory;

	@Test
	void exportsPersistentWorldStateAndStreamingVerifierAcceptsItDeterministically() throws Exception {
		Hash stateRoot;
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("state-db"))) {
			WorldState state = storage.createEmpty(false);
			AccountNonceStateImpl nonce = ((AccountNonceStateImpl) AccountNonceStateImpl.ZERO)
					.increaseNonce(1, Instant.ofEpochSecond(1_800_000_000L));
			state.setNonce(Address.ZERO, nonce);
			stateRoot = storage.persist(state);

			ChainFixture chain = chain(stateRoot);
			CheckpointStateSnapshotExporter exporter = exporter(chain, storage.factory());
			Path firstDirectory = Files.createDirectory(temporaryDirectory.resolve("export-one"));
			Path secondDirectory = Files.createDirectory(temporaryDirectory.resolve("export-two"));

			CheckpointStateSnapshotExporter.ExportResult first = exporter.export(2, firstDirectory);
			CheckpointStateSnapshotExporter.ExportResult second = exporter.export(2, secondDirectory);

			assertThat(first.manifest().headerSegment().headers()).hasSize(3);
			assertThat(first.manifest().checkpointStateRoot()).isEqualTo(stateRoot);
			assertThat(first.chunkFiles()).isNotEmpty();
			assertThat(first.chunkFiles()).allMatch(path -> path.getFileName().toString().matches("chunk-[0-9]{5}\\.bin"));
			assertThat(first.manifest().chunks())
					.allMatch(chunk -> chunk.byteCount() <= CheckpointSnapshotLimits.MAX_CHUNK_BYTES);
			String version = SnapshotFormatCompatibility.currentVersionName(
					2, first.manifest().checkpointHash());
			assertThat(first.manifest().chunks())
					.allMatch(chunk -> chunk.url().contains("/versions/" + version + "/chunks/"));

			SnapshotTransportManifest envelope = new ObjectMapper().readValue(
					first.manifestFile().toFile(), SnapshotTransportManifest.class);
			assertThat(envelope.decodeAndVerify()).isEqualTo(first.manifest());
			assertThat(first.canonicalManifestBytes())
					.isEqualTo(CheckpointSnapshotManifestCodec.canonicalBytes(first.manifest()));
			assertThat(first.manifestSigningHash())
					.isEqualTo(CheckpointSnapshotManifestCodec.signingHash(first.manifest()));

			CheckpointSnapshotVerifier verifier = new CheckpointSnapshotVerifier(chain.registry, CHAIN, 2);
			CheckpointSnapshotVerifier.VerificationResult verified = verifier.verify(
					first.manifest(), descriptor -> new BinarySnapshotNodeSource(
							first.chunkFiles().get(descriptor.index()), descriptor));
			assertThat(verified.stateRoot()).isEqualTo(stateRoot);

			assertThat(second.canonicalManifestBytes()).isEqualTo(first.canonicalManifestBytes());
			assertThat(second.chunkFiles()).hasSameSizeAs(first.chunkFiles());
			for (int index = 0; index < first.chunkFiles().size(); index++) {
				assertThat(Files.readAllBytes(second.chunkFiles().get(index)))
						.isEqualTo(Files.readAllBytes(first.chunkFiles().get(index)));
			}
		}
	}

	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void removesPartialChunksWhenHistoricalTrieTraversalDetectsCorruption() throws Exception {
		Bytes validContent = Bytes.wrap(new byte[96]);
		Hash stateRoot = Hash.hash(validContent);
		ChainFixture chain = chain(stateRoot);
		WorldStateFactory factory = mock(WorldStateFactory.class);
		WorldState state = mock(WorldState.class);
		MerkleTrie<Bytes, Bytes> mainTrie = mock(MerkleTrie.class);
		MerkleTrie corruptSubTrie = mock(MerkleTrie.class);
		Node<Bytes> rootNode = mock(Node.class);
		when(rootNode.getHash()).thenReturn(stateRoot);
		when(rootNode.getEncodedBytes()).thenReturn(validContent);
		when(rootNode.isReferencedByHash()).thenReturn(true);
		when(mainTrie.getRootHash()).thenReturn(stateRoot);
		doAnswer(invocation -> {
			Consumer<Node<Bytes>> visitor = invocation.getArgument(0);
			visitor.accept(rootNode);
			return null;
		}).when(mainTrie).visitAll(any());
		when(corruptSubTrie.getRootHash()).thenReturn(Hash.hash(Bytes.of(9)));
		doThrow(new IllegalStateException("corrupt child")).when(corruptSubTrie).visitAll(any());
		when(state.getMainTrie()).thenReturn(mainTrie);
		when(state.getBalanceTrie()).thenReturn(corruptSubTrie);
		when(factory.createForValidation(stateRoot)).thenReturn(state);
		Path output = Files.createDirectory(temporaryDirectory.resolve("failed-export"));

		assertThatThrownBy(() -> exporter(chain, factory).export(2, output))
				.isInstanceOf(SnapshotExportException.class)
				.hasMessageContaining("trie traversal failed");
		try (var files = Files.list(output)) {
			assertThat(files).isEmpty();
		}
	}

	private CheckpointStateSnapshotExporter exporter(ChainFixture chain, WorldStateFactory factory) {
		return new CheckpointStateSnapshotExporter(
				chain.registry, chain.query, factory, CHAIN, 2, PUBLIC_ORIGIN, new ObjectMapper());
	}

	private ChainFixture chain(Hash checkpointStateRoot) {
		List<StoredBlock> blocks = new ArrayList<>();
		Hash previousHash = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (long height = 0; height <= 2; height++) {
			BlockHeader header = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
					.previousHash(previousHash)
					.txRootHash(Hash.ZERO)
					.stateRootHash(height == 2 ? checkpointStateRoot : Hash.ZERO)
					.difficulty(BigInteger.ONE)
					.coinbase(Address.ZERO)
					.nonce(height)
					.signature(Signature.ZERO)
					.build();
			cumulative = cumulative.add(BigInteger.ONE);
			StoredBlock stored = mock(StoredBlock.class);
			Block block = mock(Block.class);
			when(block.getHeader()).thenReturn(header);
			when(stored.getBlock()).thenReturn(block);
			when(stored.getHash()).thenReturn(header.getHash());
			when(stored.getCumulativeDifficulty()).thenReturn(cumulative);
			blocks.add(stored);
			previousHash = header.getHash();
		}

		ChainQuery query = mock(ChainQuery.class);
		for (int height = 0; height < blocks.size(); height++) {
			Hash blockHash = blocks.get(height).getHash();
			when(query.getBlockHashByHeight(height)).thenReturn(java.util.Optional.of(blockHash));
			when(query.getStoredBlockHeaderByHeight(height)).thenReturn(java.util.Optional.of(blocks.get(height)));
		}
		when(query.getStoredBlockByHeight(2)).thenReturn(java.util.Optional.of(blocks.get(2)));
		when(query.findStoredBlockHeadersByHeightRange(0, 2)).thenReturn(List.copyOf(blocks));
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(2)).thenReturn(true);
		Hash checkpointHash = blocks.get(2).getHash();
		when(registry.verifyCheckpoint(2, checkpointHash)).thenReturn(true);
		return new ChainFixture(query, registry);
	}

	private record ChainFixture(ChainQuery query, CheckpointRegistry registry) {
	}
}
