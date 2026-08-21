/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.SnapshotHeaderSegment;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.CoreSnapshotArchiveTransportManifest;
import global.goldenera.node.core.sync.snapshot.transport.SnapshotTransportManifest;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import global.goldenera.node.core.sync.snapshot.transport.StagedSnapshotDownload;

class CoreSnapshotArchiveReplayerTest {

	private static final PrivateKey SIGNER = PrivateKey.wrap(Bytes.fromHexString("0x" + "02".repeat(32)));

	@TempDir
	Path temporaryDirectory;

	@Test
	void resumesAfterCanonicalGenesisAndPersistsReplayMetadataWithNonNullLocalSource() throws Exception {
		List<StoredBlock> chain = chain(2);
		StoredBlock genesis = chain.getFirst();
		StoredBlock checkpoint = chain.getLast();
		Bytes encodedChunk = CoreSnapshotBlockChunkCodec.encodeChunk(0, chain);
		Path chunkFile = temporaryDirectory.resolve("archive-chunk-00000.bin");
		Files.write(chunkFile, encodedChunk.toArrayUnsafe());

		StoredChainIdentity identity = new StoredChainIdentity(
				1, Network.TESTNET.getCode(), "testnet", genesis.getHash().toHexString(), null);
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				1, Network.TESTNET.getCode(), identity, checkpoint.getHeight(), checkpoint.getHash(),
				checkpoint.getBlock().getHeader().getStateRootHash(), checkpoint.getCumulativeDifficulty(),
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, List.of()), List.of());
		CoreSnapshotBlockChunkDescriptor descriptor = new CoreSnapshotBlockChunkDescriptor(
				0, 0, 1, 2, encodedChunk.size(), Hash.hash(encodedChunk));
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				1, CheckpointSnapshotManifestCodec.signingHash(stateManifest), List.of(descriptor));
		StagedSnapshotDownload stateDownload = new StagedSnapshotDownload(
				SnapshotTransportManifest.from(stateManifest), stateManifest, temporaryDirectory,
				temporaryDirectory.resolve("manifest.json"), List.of());
		StagedCoreSnapshotArchiveDownload staged = new StagedCoreSnapshotArchiveDownload(
				stateDownload, CoreSnapshotArchiveTransportManifest.from(archiveManifest), archiveManifest,
				temporaryDirectory.resolve("archive-manifest.json"), List.of(chunkFile));

		VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		when(verified.activationEligible()).thenReturn(true);
		when(verified.checkpointHeight()).thenReturn(checkpoint.getHeight());
		when(verified.checkpointHash()).thenReturn(checkpoint.getHash());
		when(verified.checkpointStateRoot()).thenReturn(checkpoint.getBlock().getHeader().getStateRootHash());
		when(verified.checkpointCumulativeDifficulty()).thenReturn(checkpoint.getCumulativeDifficulty());
		when(verified.stateManifestSigningHash()).thenReturn(CheckpointSnapshotManifestCodec.signingHash(stateManifest));
		when(verified.archiveManifestSigningHash()).thenReturn(CoreSnapshotArchiveManifestCodec.signingHash(archiveManifest));
		when(verified.blockCount()).thenReturn(2L);
		when(verified.chunkCount()).thenReturn(1);
		when(verified.encodedBytes()).thenReturn((long) encodedChunk.size());

		ChainQuery chainQuery = mock(ChainQuery.class);
		AtomicReference<StoredBlock> head = new AtomicReference<>(genesis);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(ignored -> head.get());
		when(chainQuery.getStoredBlockByHeight(0L)).thenReturn(Optional.of(genesis));
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.validateHeader(any(), any())).thenReturn(mock(StatelessValidatedHeader.class));
		when(validator.validateBlockBody(any(), any())).thenAnswer(invocation -> {
			Block block = invocation.getArgument(0);
			StatelessValidatedBlock proof = mock(StatelessValidatedBlock.class);
			when(proof.block()).thenReturn(block);
			when(proof.matches(block)).thenReturn(true);
			return proof;
		});
		BlockReorgs reorgs = mock(BlockReorgs.class);
		org.mockito.Mockito.doAnswer(invocation -> {
			ValidatedSyncBatch batch = invocation.getArgument(0);
			head.set(batch.blocks().getLast());
			return null;
		}).when(reorgs).executeAtomicReorgSwap(any());

		CoreSnapshotArchiveReplayer.ReplayResult result =
				new CoreSnapshotArchiveReplayer(chainQuery, validator, reorgs).replay(staged, verified);

		assertThat(result.replayedBlocks()).isEqualTo(1);
		assertThat(result.headHash()).isEqualTo(checkpoint.getHash());
		StoredBlock persisted = head.get();
		assertThat(persisted.getReceivedFrom()).isEqualTo(Address.ZERO);
		assertThat(persisted.getConnectedSource()).isEqualTo(ConnectedSource.SYNC);
		Bytes encoded = StoredBlockEncoder.INSTANCE.encode(persisted, StoredBlockVersion.V1);
		StoredBlock decoded = StoredBlockDecoder.INSTANCE.decode(encoded);
		assertThat(decoded.getReceivedFrom()).isEqualTo(Address.ZERO);
		assertThat(decoded.getHash()).isEqualTo(checkpoint.getHash());
		assertThat(decoded.getEvents()).isEmpty();
		assertThat(decoded.getTxCount()).isZero();
	}

	@Test
	void rebuildsStoredMetadataFromValidationProofAndDropsPublisherDerivedEvents() {
		StoredBlock archived = chain(1).getFirst().toBuilder()
				.events(List.of(new BlockEvent.FeesCollected(
						Address.ZERO, org.apache.tuweni.units.ethereum.Wei.ZERO)))
				.build();
		StatelessValidatedBlock proof = mock(StatelessValidatedBlock.class);
		when(proof.block()).thenReturn(archived.getBlock());

		StoredBlock replayed = CoreSnapshotArchiveReplayer.replayStoredBlock(archived, proof);

		assertThat(replayed.getBlock()).isSameAs(proof.block());
		assertThat(replayed.getEvents()).isEmpty();
		assertThat(replayed.getConnectedSource()).isEqualTo(ConnectedSource.SYNC);
		assertThat(replayed.getReceivedFrom()).isEqualTo(Address.ZERO);
		assertThat(replayed.getHash()).isEqualTo(archived.getBlock().getHash());
		assertThat(replayed.getTxCount()).isEqualTo(archived.getBlock().getTxs().size());
	}

	@Test
	void rejectsCapabilityFromAnotherArchiveBeforeValidationOrPersistence() throws Exception {
		ArchiveFixture fixture = archiveFixture(chain(2), List.of(2));
		when(fixture.verified().archiveManifestSigningHash())
				.thenReturn(Hash.hash(Bytes.ofUnsignedInt(999)));
		ReplayHarness harness = replayHarness(fixture.blocks(), 0);

		assertThatThrownBy(() -> harness.replayer().replay(fixture.staged(), fixture.verified()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("matching fully verified archive capability");
		verifyNoInteractions(harness.validator(), harness.reorgs());
	}

	@Test
	void carriesCompleteRandomXBatchContextAcrossArchiveChunkBoundaries() throws Exception {
		ArchiveFixture fixture = archiveFixture(chain(4), List.of(2, 2));
		ReplayHarness harness = replayHarness(fixture.blocks(), 0);

		harness.replayer().replay(fixture.staged(), fixture.verified());

		assertThat(harness.batchSizes()).containsExactly(3);
		assertThat(harness.contextsByHeight()).containsKeys(1L, 2L, 3L);
		assertThat(harness.contextsByHeight().get(2L).keySet()).containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	@Test
	void countBoundFlushesAt250AndByteBoundStillAllowsSingleOversizedBlockProgress() throws Exception {
		ArchiveFixture fixture = archiveFixture(chain(252), List.of(252));
		ReplayHarness harness = replayHarness(fixture.blocks(), 0);

		harness.replayer().replay(fixture.staged(), fixture.verified());

		assertThat(harness.batchSizes()).containsExactly(250, 1);
		assertThat(CoreSnapshotArchiveReplayer.shouldFlushReplayBatch(
				250, 1, 1)).isTrue();
		assertThat(CoreSnapshotArchiveReplayer.shouldFlushReplayBatch(
				1, CoreSnapshotArchiveReplayer.MAX_REPLAY_BATCH_BYTES - 1, 1)).isFalse();
		assertThat(CoreSnapshotArchiveReplayer.shouldFlushReplayBatch(
				1, CoreSnapshotArchiveReplayer.MAX_REPLAY_BATCH_BYTES - 1, 2)).isTrue();
		assertThat(CoreSnapshotArchiveReplayer.shouldFlushReplayBatch(
				0, 0, CoreSnapshotArchiveReplayer.MAX_REPLAY_BATCH_BYTES + 1)).isFalse();
	}

	@Test
	void rejectsResumeWhoseCanonicalHashMatchesButStoredDifficultyIsCorrupt() throws Exception {
		ArchiveFixture fixture = archiveFixture(chain(3), List.of(3));
		ReplayHarness harness = replayHarness(fixture.blocks(), 1);
		StoredBlock corruptHead = fixture.blocks().get(1).toBuilder()
				.cumulativeDifficulty(BigInteger.valueOf(999))
				.build();
		harness.head().set(corruptHead);

		assertThatThrownBy(() -> harness.replayer().replay(fixture.staged(), fixture.verified()))
				.isInstanceOf(global.goldenera.node.shared.exceptions.GEFailedException.class)
				.hasMessageContaining("differs from verified archive");
		verifyNoInteractions(harness.validator(), harness.reorgs());
	}

	@Test
	void corruptTailAfterCommittedBatchLeavesValidCanonicalPrefixForV1Resume() throws Exception {
		ArchiveFixture fixture = archiveFixture(chain(252), List.of(252));
		Path chunk = fixture.staged().blockChunkFiles().getFirst();
		Files.write(chunk, Bytes.concatenate(Bytes.wrap(Files.readAllBytes(chunk)), Bytes.of(0x7f)).toArrayUnsafe());
		ReplayHarness harness = replayHarness(fixture.blocks(), 0);

		assertThatThrownBy(() -> harness.replayer().replay(fixture.staged(), fixture.verified()))
				.isInstanceOf(Exception.class);
		assertThat(harness.head().get().getHeight()).isEqualTo(250);
		assertThat(harness.head().get().getHash()).isEqualTo(fixture.blocks().get(250).getHash());
		assertThat(harness.batchSizes()).containsExactly(250);
	}

	private List<StoredBlock> chain(int blockCount) {
		java.util.ArrayList<StoredBlock> blocks = new java.util.ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (int height = 0; height < blockCount; height++) {
			BigInteger difficulty = BigInteger.valueOf(height + 1L);
			cumulative = cumulative.add(difficulty);
			Hash stateRoot = Hash.hash(Bytes.ofUnsignedInt(100 + height));
			BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
					.previousHash(previous)
					.txRootHash(Hash.ZERO)
					.stateRootHash(stateRoot)
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

	private ArchiveFixture archiveFixture(List<StoredBlock> blocks, List<Integer> chunkSizes) throws Exception {
		StoredBlock genesis = blocks.getFirst();
		StoredBlock checkpoint = blocks.getLast();
		StoredChainIdentity identity = new StoredChainIdentity(
				1, Network.TESTNET.getCode(), "testnet", genesis.getHash().toHexString(), null);
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				1, Network.TESTNET.getCode(), identity, checkpoint.getHeight(), checkpoint.getHash(),
				checkpoint.getBlock().getHeader().getStateRootHash(), checkpoint.getCumulativeDifficulty(),
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, List.of()), List.of());
		List<CoreSnapshotBlockChunkDescriptor> descriptors = new ArrayList<>();
		List<Path> files = new ArrayList<>();
		int offset = 0;
		long encodedBytes = 0;
		for (int index = 0; index < chunkSizes.size(); index++) {
			int count = chunkSizes.get(index);
			Bytes encoded = CoreSnapshotBlockChunkCodec.encodeChunk(
					index, blocks.subList(offset, offset + count));
			Path file = temporaryDirectory.resolve("archive-chunk-" + index + ".bin");
			Files.write(file, encoded.toArrayUnsafe());
			files.add(file);
			descriptors.add(new CoreSnapshotBlockChunkDescriptor(
					index, offset, offset + count - 1L, count, encoded.size(), Hash.hash(encoded)));
			encodedBytes += encoded.size();
			offset += count;
		}
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				1, CheckpointSnapshotManifestCodec.signingHash(stateManifest), descriptors);
		StagedSnapshotDownload stateDownload = new StagedSnapshotDownload(
				SnapshotTransportManifest.from(stateManifest), stateManifest, temporaryDirectory,
				temporaryDirectory.resolve("manifest.json"), List.of());
		StagedCoreSnapshotArchiveDownload staged = new StagedCoreSnapshotArchiveDownload(
				stateDownload, CoreSnapshotArchiveTransportManifest.from(archiveManifest), archiveManifest,
				temporaryDirectory.resolve("archive-manifest.json"), files);
		VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		when(verified.activationEligible()).thenReturn(true);
		when(verified.checkpointHeight()).thenReturn(checkpoint.getHeight());
		when(verified.checkpointHash()).thenReturn(checkpoint.getHash());
		when(verified.checkpointStateRoot()).thenReturn(checkpoint.getBlock().getHeader().getStateRootHash());
		when(verified.checkpointCumulativeDifficulty()).thenReturn(checkpoint.getCumulativeDifficulty());
		when(verified.stateManifestSigningHash()).thenReturn(CheckpointSnapshotManifestCodec.signingHash(stateManifest));
		when(verified.archiveManifestSigningHash())
				.thenReturn(CoreSnapshotArchiveManifestCodec.signingHash(archiveManifest));
		when(verified.blockCount()).thenReturn((long) blocks.size());
		when(verified.chunkCount()).thenReturn(descriptors.size());
		long totalEncodedBytes = encodedBytes;
		when(verified.encodedBytes()).thenReturn(totalEncodedBytes);
		return new ArchiveFixture(blocks, staged, verified);
	}

	private ReplayHarness replayHarness(List<StoredBlock> blocks, int localHeadIndex) throws Exception {
		AtomicReference<StoredBlock> head = new AtomicReference<>(blocks.get(localHeadIndex));
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(ignored -> head.get());
		for (StoredBlock block : blocks) {
			when(chainQuery.getStoredBlockByHeight(block.getHeight())).thenAnswer(ignored -> {
				StoredBlock current = head.get();
				return block.getHeight() <= current.getHeight() ? Optional.of(
						block.getHeight() == current.getHeight() ? current : block) : Optional.empty();
			});
		}
		Map<Long, Map<Long, Hash>> contexts = new LinkedHashMap<>();
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.validateHeader(any(), any())).thenAnswer(invocation -> {
			BlockHeader header = invocation.getArgument(0);
			Map<Long, Hash> context = invocation.getArgument(1);
			contexts.put(header.getHeight(), Map.copyOf(context));
			return mock(StatelessValidatedHeader.class);
		});
		when(validator.validateBlockBody(any(), any())).thenAnswer(invocation -> {
			Block block = invocation.getArgument(0);
			StatelessValidatedBlock proof = mock(StatelessValidatedBlock.class);
			when(proof.block()).thenReturn(block);
			when(proof.matches(block)).thenReturn(true);
			return proof;
		});
		List<Integer> batchSizes = new ArrayList<>();
		BlockReorgs reorgs = mock(BlockReorgs.class);
		org.mockito.Mockito.doAnswer(invocation -> {
			ValidatedSyncBatch batch = invocation.getArgument(0);
			assertThat(batch.commonAncestor()).isSameAs(head.get());
			assertThat(batch.blocks().getFirst().getHeight())
					.isEqualTo(batch.commonAncestor().getHeight() + 1);
			batchSizes.add(batch.blocks().size());
			head.set(batch.blocks().getLast());
			return null;
		}).when(reorgs).executeAtomicReorgSwap(any());
		return new ReplayHarness(new CoreSnapshotArchiveReplayer(chainQuery, validator, reorgs),
				validator, reorgs, head, contexts, batchSizes);
	}

	private record ArchiveFixture(
			List<StoredBlock> blocks,
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified) {
	}

	private record ReplayHarness(
			CoreSnapshotArchiveReplayer replayer,
			BlockValidator validator,
			BlockReorgs reorgs,
			AtomicReference<StoredBlock> head,
			Map<Long, Map<Long, Hash>> contextsByHeight,
			List<Integer> batchSizes) {
	}
}
