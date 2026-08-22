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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.state.impl.AccountNonceStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveChunkSource;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveExporter;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.api.v1.sync.CheckpointSnapshotApiV1;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotArtifactExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotBinding;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotChunkDescriptor;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotManifest;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotManifestCodec;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotException;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotTable;

class CheckpointSnapshotPublicationServiceTest {

	private static final PrivateKey SIGNER = PrivateKey.wrap(Bytes.fromHexString("0x" + "01".repeat(32)));

	@TempDir
	Path temporaryDirectory;

	@Test
	void atomicallyPublishesVerifiedControllerCompatibleCombinedArtifacts() throws Exception {
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("state-db"))) {
			Fixture fixture = fixture(storage);
			Path parent = Files.createDirectory(temporaryDirectory.resolve("publish-parent"));
			Path target = parent.resolve("checkpoint-2");

			CheckpointSnapshotPublicationService.PublicationResult result =
					fixture.publicationService.prepareCombined(2, target);

			assertThat(result.publicationDirectory()).isEqualTo(target);
			assertThat(result.verifiedArchive().activationEligible()).isTrue();
			assertThat(result.archiveManifest().stateManifestSigningHash())
					.isEqualTo(result.stateManifestSigningHash());
			List<String> names;
			try (var files = Files.list(target)) {
				names = files.map(path -> path.getFileName().toString()).sorted().toList();
			}
			assertThat(names).contains("manifest.json", "archive-manifest.json", "chunk-00000.bin",
					"archive-chunk-00000.bin");
			assertThat(names).allMatch(name -> !name.endsWith(".part"));
			try (var siblings = Files.list(parent)) {
				assertThat(siblings.map(path -> path.getFileName().toString()).toList())
						.containsExactly("checkpoint-2");
			}

			SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
			properties.setPublishEnabled(true);
			properties.setPublishDirectory(target);
			properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
			CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(properties);
			assertThat(body(controller.manifest())).isEqualTo(Files.readAllBytes(target.resolve("manifest.json")));
			assertThat(body(controller.chunk("0"))).isEqualTo(Files.readAllBytes(target.resolve("chunk-00000.bin")));
			assertThat(body(controller.archiveManifest()))
					.isEqualTo(Files.readAllBytes(target.resolve("archive-manifest.json")));
			assertThat(body(controller.archiveChunk("0")))
					.isEqualTo(Files.readAllBytes(target.resolve("archive-chunk-00000.bin")));

			assertThatThrownBy(() -> fixture.publicationService.prepareCombined(2, target))
					.isInstanceOf(SnapshotExportException.class)
					.hasMessageContaining("already exists");
			assertThat(Files.exists(target.resolve("manifest.json"))).isTrue();
		}
	}

	@Test
	void verifierFailureRemovesSiblingStagingAndNeverCreatesPublishTarget() throws Exception {
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("cleanup-state-db"))) {
			Fixture fixture = fixture(storage);
			CoreSnapshotArchiveVerifier rejectingVerifier = mock(CoreSnapshotArchiveVerifier.class);
			when(rejectingVerifier.verify(
					any(CoreSnapshotArchiveManifest.class), any(CheckpointSnapshotManifest.class),
					any(SnapshotChunkSource.class), any(CoreSnapshotArchiveChunkSource.class)))
					.thenThrow(new SnapshotVerificationException("injected corruption"));
			CheckpointSnapshotPublicationService publication = new CheckpointSnapshotPublicationService(
					fixture.stateExporter, fixture.archiveExporter, rejectingVerifier);
			Path parent = Files.createDirectory(temporaryDirectory.resolve("cleanup-publish-parent"));
			Path target = parent.resolve("checkpoint-2");

			assertThatThrownBy(() -> publication.prepareCombined(2, target))
					.isInstanceOf(SnapshotExportException.class)
					.hasMessageContaining("Combined checkpoint snapshot publication failed");
			assertThat(Files.notExists(target)).isTrue();
			try (var files = Files.list(parent)) {
				assertThat(files).isEmpty();
			}
		}
	}

	@Test
	void publishesExplorerArtifactsBoundToTheGeneratedCoreManifests() throws Exception {
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("explorer-publication-state-db"))) {
			Fixture fixture = fixture(storage);
			ObjectMapper objectMapper = new ObjectMapper();
			ExplorerSnapshotManifestCodec codec = new ExplorerSnapshotManifestCodec(objectMapper);
			ExplorerCheckpointSnapshotExporter explorerExporter = mock(ExplorerCheckpointSnapshotExporter.class);
			byte[] explorerChunk = "explorer-snapshot".getBytes(StandardCharsets.UTF_8);
			when(explorerExporter.export(any(), any(Path.class), eq(64 * 1024))).thenAnswer(invocation -> {
				ExplorerSnapshotBinding binding = invocation.getArgument(0, ExplorerSnapshotBinding.class);
				Path destination = invocation.getArgument(1, Path.class);
				Files.createDirectory(destination);
				String fileName = "explorer-status-0.bin";
				Files.write(destination.resolve(fileName), explorerChunk);
				ExplorerSnapshotChunkDescriptor descriptor = new ExplorerSnapshotChunkDescriptor(
						ExplorerSnapshotTable.STATUS, ExplorerSnapshotTable.SCHEMA_VERSION, 0, 1,
						explorerChunk.length,
						HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(explorerChunk)),
						fileName);
				ExplorerSnapshotManifest manifest = codec.sign(new ExplorerSnapshotManifest(
						ExplorerSnapshotManifest.FORMAT_VERSION, binding.carrierNetworkCode(), binding.chainId(),
						binding.genesisHash(), binding.checkpointHeight(), binding.checkpointHash(),
						binding.checkpointStateRoot(), binding.coreStateSigningHash(),
						binding.coreArchiveSigningHash(), "77".repeat(32),
						Map.of(ExplorerSnapshotTable.STATUS.tableName(), ExplorerSnapshotTable.SCHEMA_VERSION),
						Map.of(ExplorerSnapshotTable.STATUS.tableName(), 1L), List.of(descriptor), null));
				Files.write(destination.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME),
						codec.encode(manifest));
				return manifest;
			});
			Path target = Files.createDirectory(temporaryDirectory.resolve("explorer-publication-parent"))
					.resolve("checkpoint-2");

			CheckpointSnapshotPublicationService.PublicationResult result = fixture.publicationService
					.prepareCombinedWithOptionalExplorer(2, target, true, explorerExporter, 64 * 1024);

			assertThat(result.explorerManifest()).isNotNull();
			assertThat(result.explorerManifest().checkpointHeight()).isEqualTo(2);
			assertThat(result.explorerChunkCount()).isEqualTo(1);
			assertThat(result.explorerManifest().coreStateSigningHash())
					.isEqualTo(result.stateManifestSigningHash().toHexString().substring(2));
			assertThat(result.explorerManifest().coreArchiveSigningHash())
					.isEqualTo(result.archiveManifestSigningHash().toHexString().substring(2));
			assertThat(Files.readAllBytes(target.resolve("explorer-status-0.bin"))).isEqualTo(explorerChunk);
			assertThat(Files.readAllBytes(target.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME)))
					.isEqualTo(codec.encode(result.explorerManifest()));
		}
	}

	@Test
	void optionalLaggingExplorerIsOmittedWithoutInvalidatingCorePublication() throws Exception {
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("lagging-explorer-state-db"))) {
			Fixture fixture = fixture(storage);
			ExplorerSnapshotArtifactExporter explorerExporter = mock(ExplorerSnapshotArtifactExporter.class);
			when(explorerExporter.export(any(), any(Path.class), eq(64 * 1024)))
					.thenThrow(new ExplorerSnapshotException("Explorer is not indexed exactly at core head"));
			Path target = Files.createDirectory(temporaryDirectory.resolve("lagging-explorer-parent"))
					.resolve("current-head");

			CheckpointSnapshotPublicationService.PublicationResult result = fixture.publicationService
					.prepareCombinedWithOptionalExplorer(2, target, true, explorerExporter, 64 * 1024);

			assertThat(result.verifiedArchive().activationEligible()).isTrue();
			assertThat(result.explorerManifest()).isNull();
			assertThat(result.explorerChunkCount()).isZero();
			assertThat(target.resolve("manifest.json")).exists();
			assertThat(target.resolve("archive-manifest.json")).exists();
			assertThat(target.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME)).doesNotExist();
		}
	}

	@Test
	void disabledExplorerPerformsZeroExplorerInteractions() throws Exception {
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(temporaryDirectory.resolve("disabled-explorer-state-db"))) {
			Fixture fixture = fixture(storage);
			ExplorerSnapshotArtifactExporter explorerExporter = mock(ExplorerSnapshotArtifactExporter.class);
			Path target = Files.createDirectory(temporaryDirectory.resolve("disabled-explorer-parent"))
					.resolve("current-head");

			CheckpointSnapshotPublicationService.PublicationResult result = fixture.publicationService
					.prepareCombinedWithOptionalExplorer(2, target, false, explorerExporter, 64 * 1024);

			verify(explorerExporter, never()).export(any(), any(), eq(64 * 1024));
			assertThat(result.explorerManifest()).isNull();
			assertThat(result.explorerChunkCount()).isZero();
			assertThat(target.resolve("manifest.json")).exists();
		}
	}

	private Fixture fixture(PersistentWorldStateTestSupport storage) throws Exception {
		WorldState state = storage.createEmpty(false);
		AccountNonceStateImpl nonce = ((AccountNonceStateImpl) AccountNonceStateImpl.ZERO)
				.increaseNonce(1, Instant.ofEpochSecond(1_800_000_000L));
		state.setNonce(Address.ZERO, nonce);
		Hash stateRoot = storage.persist(state);
		List<StoredBlock> blocks = chain(stateRoot);
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 1, "publication-test",
				blocks.getFirst().getHash().toHexString(), null);
		ChainQuery chainQuery = mock(ChainQuery.class);
		for (int height = 0; height < blocks.size(); height++) {
			StoredBlock block = blocks.get(height);
			when(chainQuery.getStoredBlockByHeight(height)).thenReturn(Optional.of(block));
			when(chainQuery.getStoredBlockHeaderByHeight(height)).thenReturn(Optional.of(block));
			when(chainQuery.getBlockHashByHeight(height)).thenReturn(Optional.of(block.getHash()));
		}
		when(chainQuery.findStoredBlockHeadersByHeightRange(0, 2)).thenReturn(blocks);
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(2)).thenReturn(true);
		when(registry.verifyCheckpoint(2, blocks.getLast().getHash())).thenReturn(true);
		ObjectMapper objectMapper = new ObjectMapper();
		CheckpointStateSnapshotExporter stateExporter = new CheckpointStateSnapshotExporter(
				registry, chainQuery, storage.factory(), identity, 2,
				URI.create("https://snapshots.example.test/"), objectMapper);
		CoreSnapshotArchiveExporter archiveExporter = new CoreSnapshotArchiveExporter(
				registry, chainQuery, identity, objectMapper);
		CheckpointSnapshotVerifier stateVerifier = new CheckpointSnapshotVerifier(registry, identity, 2);
		CoreSnapshotArchiveVerifier fullVerifier = new CoreSnapshotArchiveVerifier(stateVerifier);
		CheckpointSnapshotPublicationService publication = new CheckpointSnapshotPublicationService(
				stateExporter, archiveExporter, fullVerifier);
		return new Fixture(stateExporter, archiveExporter, publication);
	}

	private List<StoredBlock> chain(Hash checkpointStateRoot) {
		List<StoredBlock> blocks = new ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (int height = 0; height <= 2; height++) {
			BigInteger difficulty = BigInteger.valueOf(height + 1L);
			cumulative = cumulative.add(difficulty);
			BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
					.previousHash(previous)
					.txRootHash(Hash.ZERO)
					.stateRootHash(height == 2 ? checkpointStateRoot : Hash.ZERO)
					.difficulty(difficulty)
					.coinbase(Address.ZERO)
					.nonce(height)
					.build();
			BlockHeader header = unsigned.toBuilder()
					.signature(SIGNER.sign(BlockHeaderUtil.hashForSigning(unsigned)))
					.build();
			Block block = BlockImpl.builder().header(header).txs(List.of()).build();
			StoredBlock stored = StoredBlock.builder()
					.block(block)
					.cumulativeDifficulty(cumulative)
					.receivedAt(header.getTimestamp())
					.receivedFrom(Address.ZERO)
					.connectedSource(height == 0 ? ConnectedSource.GENESIS : ConnectedSource.SYNC)
					.identity(header.getIdentity())
					.computeIndexes()
					.build();
			blocks.add(stored);
			previous = stored.getHash();
		}
		return List.copyOf(blocks);
	}

	private byte[] body(ResponseEntity<Resource> response) throws Exception {
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		try (var input = response.getBody().getInputStream()) {
			return input.readAllBytes();
		}
	}

	private record Fixture(
			CheckpointStateSnapshotExporter stateExporter,
			CoreSnapshotArchiveExporter archiveExporter,
			CheckpointSnapshotPublicationService publicationService) {
	}
}
