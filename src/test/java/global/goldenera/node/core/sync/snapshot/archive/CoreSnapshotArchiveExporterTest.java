/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.SnapshotExportException;
import global.goldenera.node.core.sync.snapshot.SnapshotHeader;
import global.goldenera.node.core.sync.snapshot.SnapshotHeaderSegment;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;
import global.goldenera.node.core.sync.snapshot.transport.CoreSnapshotArchiveTransportManifest;

class CoreSnapshotArchiveExporterTest {

	private static final PrivateKey SIGNER = PrivateKey.wrap(Bytes.fromHexString("0x" + "01".repeat(32)));
	private static final Hash CHECKPOINT_STATE_ROOT = Hash.hash(Bytes.ofUnsignedInt(77));

	@TempDir
	Path temporaryDirectory;

	@Test
	void exportsVerifiableDeterministicFullArchiveBoundToStateManifest() throws Exception {
		List<StoredBlock> blocks = chain(3);
		Fixture fixture = fixture(blocks);
		long oneBlockChunkLimit = CoreSnapshotBlockChunkCodec.HEADER_BYTES + blocks.stream()
				.map(CoreSnapshotBlockChunkCodec::encodeStoredBlock)
				.mapToLong(encoded -> Integer.BYTES + encoded.size())
				.max().orElseThrow();
		CoreSnapshotArchiveExporter exporter = new CoreSnapshotArchiveExporter(
				fixture.registry, fixture.query, fixture.identity, oneBlockChunkLimit, new ObjectMapper());
		Path firstDirectory = Files.createDirectory(temporaryDirectory.resolve("archive-one"));
		Path secondDirectory = Files.createDirectory(temporaryDirectory.resolve("archive-two"));

		CoreSnapshotArchiveExporter.ExportResult first = exporter.export(
				2, fixture.stateManifest, firstDirectory);
		CoreSnapshotArchiveExporter.ExportResult second = exporter.export(
				2, fixture.stateManifest, secondDirectory);

		assertThat(first.manifest().stateManifestSigningHash())
				.isEqualTo(CheckpointSnapshotManifestCodec.signingHash(fixture.stateManifest));
		assertThat(first.manifest().blockChunks()).hasSize(3);
		assertThat(first.manifest().blockChunks())
				.extracting(CoreSnapshotBlockChunkDescriptor::firstHeight)
				.containsExactly(0L, 1L, 2L);
		assertThat(first.manifest().blockChunks())
				.allMatch(chunk -> chunk.byteCount() <= oneBlockChunkLimit);
		assertThat(first.canonicalManifestBytes())
				.isEqualTo(CoreSnapshotArchiveManifestCodec.canonicalBytes(first.manifest()));
		assertThat(first.manifestSigningHash())
				.isEqualTo(CoreSnapshotArchiveManifestCodec.signingHash(first.manifest()));

		CoreSnapshotArchiveTransportManifest envelope = new ObjectMapper().readValue(
				first.manifestFile().toFile(), CoreSnapshotArchiveTransportManifest.class);
		assertThat(envelope.decodeAndVerify()).isEqualTo(first.manifest());

		CheckpointSnapshotVerifier stateVerifier = mock(CheckpointSnapshotVerifier.class);
		SnapshotChunkSource stateSource = ignored -> null;
		when(stateVerifier.verifyWithFullHistoryAnchor(
				same(fixture.stateManifest), same(stateSource), any(VerifiedCoreArchiveHistory.class)))
				.thenReturn(fixture.stateResult);
		VerifiedCoreSnapshotArchive verified = new CoreSnapshotArchiveVerifier(stateVerifier).verify(
				first.manifest(), fixture.stateManifest, stateSource,
				descriptor -> Files.newInputStream(first.chunkFiles().get(descriptor.index())));
		assertThat(verified.activationEligible()).isTrue();
		assertThat(verified.blockCount()).isEqualTo(3);
		assertThat(verified.checkpointHash()).isEqualTo(blocks.getLast().getHash());

		assertThat(second.canonicalManifestBytes()).isEqualTo(first.canonicalManifestBytes());
		assertThat(second.chunkFiles()).hasSameSizeAs(first.chunkFiles());
		for (int index = 0; index < first.chunkFiles().size(); index++) {
			assertThat(Files.readAllBytes(second.chunkFiles().get(index)))
					.isEqualTo(Files.readAllBytes(first.chunkFiles().get(index)));
		}
	}

	@Test
	void removesPartialArchiveWhenCanonicalStoredBlockIsCorrupt() throws Exception {
		List<StoredBlock> valid = chain(3);
		StoredBlock corrupt = valid.get(1).toBuilder()
				.cumulativeDifficulty(BigInteger.valueOf(999))
				.build();
		List<StoredBlock> source = List.of(valid.getFirst(), corrupt, valid.getLast());
		Fixture fixture = fixture(source, valid.getLast(), valid);
		Path output = Files.createDirectory(temporaryDirectory.resolve("failed-archive"));

		assertThatThrownBy(() -> new CoreSnapshotArchiveExporter(
				fixture.registry, fixture.query, fixture.identity, new ObjectMapper())
				.export(2, fixture.stateManifest, output))
				.isInstanceOf(SnapshotExportException.class)
				.hasMessageContaining("cumulative difficulty mismatch");
		try (var files = Files.list(output)) {
			assertThat(files).isEmpty();
		}
	}

	private Fixture fixture(List<StoredBlock> blocks) {
		return fixture(blocks, blocks.getLast(), blocks);
	}

	private Fixture fixture(
			List<StoredBlock> streamedBlocks,
			StoredBlock canonicalCheckpoint,
			List<StoredBlock> manifestBlocks) {
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 1, "archive-test",
				manifestBlocks.getFirst().getHash().toHexString(), null);
		List<SnapshotHeader> headers = manifestBlocks.stream()
				.map(block -> new SnapshotHeader(
						block.getHash(), block.getBlock().getHeader(), block.getCumulativeDifficulty()))
				.toList();
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				CheckpointSnapshotLimits.FORMAT_VERSION,
				identity.carrierNetworkCode(),
				identity,
				2,
				canonicalCheckpoint.getHash(),
				canonicalCheckpoint.getBlock().getHeader().getStateRootHash(),
				canonicalCheckpoint.getCumulativeDifficulty(),
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, headers),
				List.of());
		Hash stateSigningHash = CheckpointSnapshotManifestCodec.signingHash(stateManifest);
		CheckpointSnapshotVerifier.VerificationResult stateResult =
				new CheckpointSnapshotVerifier.VerificationResult(
						2, canonicalCheckpoint.getHash(), CHECKPOINT_STATE_ROOT, 1, 0, stateSigningHash);

		ChainQuery query = mock(ChainQuery.class);
		for (int height = 0; height < streamedBlocks.size(); height++) {
			StoredBlock block = streamedBlocks.get(height);
			when(query.getStoredBlockByHeight(height)).thenReturn(Optional.of(block));
			when(query.getBlockHashByHeight(height)).thenReturn(Optional.of(block.getHash()));
		}
		when(query.getStoredBlockByHeight(2)).thenReturn(Optional.of(canonicalCheckpoint));
		when(query.getBlockHashByHeight(2)).thenReturn(Optional.of(canonicalCheckpoint.getHash()));
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(2)).thenReturn(true);
		when(registry.verifyCheckpoint(2, canonicalCheckpoint.getHash())).thenReturn(true);
		return new Fixture(query, registry, identity, stateManifest, stateResult);
	}

	private List<StoredBlock> chain(int blockCount) {
		List<StoredBlock> blocks = new ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (int height = 0; height < blockCount; height++) {
			BigInteger difficulty = BigInteger.valueOf(height + 1L);
			cumulative = cumulative.add(difficulty);
			BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
					.previousHash(previous)
					.txRootHash(Hash.ZERO)
					.stateRootHash(height == blockCount - 1 ? CHECKPOINT_STATE_ROOT : Hash.ZERO)
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

	private record Fixture(
			ChainQuery query,
			CheckpointRegistry registry,
			StoredChainIdentity identity,
			CheckpointSnapshotManifest stateManifest,
			CheckpointSnapshotVerifier.VerificationResult stateResult) {
	}
}
