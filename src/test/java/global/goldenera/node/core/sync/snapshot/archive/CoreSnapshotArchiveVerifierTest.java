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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.merkletrie.NodeLoader;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.CheckpointStateSupplementVerifier;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;
import global.goldenera.node.core.sync.snapshot.SnapshotHeader;
import global.goldenera.node.core.sync.snapshot.SnapshotHeaderSegment;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;

class CoreSnapshotArchiveVerifierTest {

	private static final PrivateKey SIGNER = PrivateKey.wrap(Bytes.fromHexString("0x" + "01".repeat(32)));
	private static final Hash CHECKPOINT_STATE_ROOT = Hash.hash(Bytes.ofUnsignedInt(77));

	@Test
	void verifiesCompleteArchiveAndOnlyReturnsActivationCapabilityAfterStateVerification() throws Exception {
		List<StoredBlock> blocks = chain(3);
		Fixture fixture = fixture(blocks, List.of(2, 1));
		CheckpointSnapshotVerifier stateVerifier = mock(CheckpointSnapshotVerifier.class);
		SnapshotChunkSource stateSource = ignored -> {
			throw new AssertionError("mocked state verifier must own state source consumption");
		};
		VerifiedCoreSnapshotArchive verified = verifier(stateVerifier, fixture.stateResult()).verify(
				fixture.archiveManifest(), fixture.stateManifest(), stateSource, fixture.chunkSource());

		verify(stateVerifier).verifyWithFullHistoryAnchor(
				same(fixture.stateManifest()), same(stateSource), any(VerifiedCoreArchiveHistory.class),
				any(CheckpointStateSupplementVerifier.class));
		verify(stateVerifier, never()).verify(same(fixture.stateManifest()), same(stateSource));
		assertThat(verified.activationEligible()).isTrue();
		assertThat(verified.blockCount()).isEqualTo(3);
		assertThat(verified.chunkCount()).isEqualTo(2);
		assertThat(verified.checkpointHash()).isEqualTo(fixture.stateManifest().checkpointHash());
		assertThat(verified.checkpointStateRoot()).isEqualTo(CHECKPOINT_STATE_ROOT);
		assertThat(verified.stateManifestSigningHash())
				.isEqualTo(CheckpointSnapshotManifestCodec.signingHash(fixture.stateManifest()));
		assertThat(verified.archiveManifestSigningHash())
				.isEqualTo(CoreSnapshotArchiveManifestCodec.signingHash(fixture.archiveManifest()));
		assertThat(verified.requiresDerivedDataRebuild()).isTrue();
		assertThat(CoreSnapshotBlockChunkCodec.encodeChunk(0, blocks.subList(0, 2)))
				.isEqualTo(CoreSnapshotBlockChunkCodec.encodeChunk(0, blocks.subList(0, 2)));
		assertThat(VerifiedCoreSnapshotArchive.class.getConstructors()).isEmpty();
		assertThat(List.of(CheckpointSnapshotVerifier.VerificationResult.class.getMethods()).stream()
				.noneMatch(method -> method.getName().equals("activationEligible"))).isTrue();

		Bytes canonical = CoreSnapshotArchiveManifestCodec.canonicalBytes(fixture.archiveManifest());
		assertThat(CoreSnapshotArchiveManifestCodec.canonicalBytes(
				CoreSnapshotArchiveManifestCodec.decodeCanonicalBytes(canonical))).isEqualTo(canonical);
	}

	@Test
	void rejectsArchiveBoundToAnotherStateManifest() {
		Fixture fixture = fixture(chain(2), List.of(2));
		CoreSnapshotArchiveManifest wrongBinding = new CoreSnapshotArchiveManifest(
				fixture.archiveManifest().formatVersion(), Hash.hash(Bytes.ofUnsignedInt(999)),
				fixture.archiveManifest().blockChunks());

		assertFailure(fixture.withManifest(wrongBinding), "not bound to the verified state manifest");
	}

	@Test
	void mintsExactNonGenesisSegmentParentAnchorFromFullHistory() throws Exception {
		Fixture fixture = withCheckpointOnlyHeaderSegment(fixture(chain(3), List.of(3)), false);
		CheckpointSnapshotVerifier stateVerifier = mock(CheckpointSnapshotVerifier.class);
		ArgumentCaptor<VerifiedCoreArchiveHistory> anchorCaptor =
				ArgumentCaptor.forClass(VerifiedCoreArchiveHistory.class);

		verifier(stateVerifier, fixture.stateResult()).verify(
				fixture.archiveManifest(), fixture.stateManifest(), ignored -> null, fixture.chunkSource());

		verify(stateVerifier).verifyWithFullHistoryAnchor(
				same(fixture.stateManifest()), any(SnapshotChunkSource.class), anchorCaptor.capture(),
				any(CheckpointStateSupplementVerifier.class));
		VerifiedCoreArchiveHistory anchor = anchorCaptor.getValue();
		SnapshotHeaderSegment segment = fixture.stateManifest().headerSegment();
		assertThat(anchor.findCumulativeDifficulty(1, segment.parentHash()))
				.contains(segment.parentCumulativeDifficulty());
		assertThat(anchor.findCumulativeDifficulty(0, segment.parentHash())).isEmpty();
		assertThat(anchor.findCumulativeDifficulty(1, Hash.ZERO)).isEmpty();
	}

	@Test
	void rejectsWrongArchiveDerivedSegmentAnchorBeforeStateVerification() {
		Fixture fixture = withCheckpointOnlyHeaderSegment(fixture(chain(3), List.of(3)), true);

		assertFailure(fixture, "does not prove the state header segment work anchor");
	}

	@Test
	void rejectsCorruptChunk() {
		Fixture fixture = fixture(chain(2), List.of(2));
		byte[] corrupt = fixture.chunks().get(0).toArray();
		corrupt[0] ^= 1;

		assertFailure(fixture.withChunk(0, Bytes.wrap(corrupt)), "chunk header");
	}

	@Test
	void rejectsGapAndDuplicateBlockHeights() {
		List<StoredBlock> valid = chain(2);
		StoredBlock gap = storedBlock(2, valid.getFirst().getHash(), CHECKPOINT_STATE_ROOT, BigInteger.TWO, Hash.ZERO);
		Fixture gapFixture = fixture(List.of(valid.getFirst(), gap), List.of(2));
		assertFailure(gapFixture, "height/body mismatch");

		Fixture duplicateFixture = fixture(List.of(valid.getFirst(), valid.getFirst()), List.of(2));
		assertFailure(duplicateFixture, "height/body mismatch");
	}

	@Test
	void rejectsForgedStoredCumulativeDifficultyEvenWithValidChunkHash() {
		StoredBlock valid = chain(1).getFirst();
		StoredBlock forged = valid.toBuilder().cumulativeDifficulty(BigInteger.valueOf(999)).build();
		Fixture fixture = fixture(List.of(forged), List.of(1));

		assertFailure(fixture, "cumulative difficulty mismatch");
	}

	@Test
	void rejectsBodyWhoseTransactionsDoNotMatchHeaderRoot() {
		StoredBlock wrongRoot = storedBlock(
				0, Hash.ZERO, CHECKPOINT_STATE_ROOT, BigInteger.ONE, Hash.hash(Bytes.ofUnsignedInt(123)));
		Fixture fixture = fixture(List.of(wrongRoot), List.of(1));

		assertFailure(fixture, "transaction root mismatch");
	}

	@Test
	void rejectsTruncatedAndTrailingChunkEvenWhenDescriptorHashMatchesPayload() {
		Fixture base = fixture(chain(1), List.of(1));
		Bytes original = base.chunks().get(0);
		Fixture truncated = withReplacedPayloadAndDescriptor(
				base, original.slice(0, original.size() - 1));
		assertFailure(truncated, "Cannot finish archive block chunk");

		Bytes trailingPayload = Bytes.concatenate(original, Bytes.of(0x7f));
		Fixture trailing = withReplacedPayloadAndDescriptor(base, trailingPayload);
		assertFailure(trailing, "Cannot finish archive block chunk");
	}

	@Test
	void rejectsDescriptorRangeGapBeforeOpeningAnyChunk() {
		Fixture fixture = fixture(chain(2), List.of(1, 1));
		List<CoreSnapshotBlockChunkDescriptor> descriptors = new ArrayList<>(
				fixture.archiveManifest().blockChunks());
		CoreSnapshotBlockChunkDescriptor second = descriptors.get(1);
		descriptors.set(1, new CoreSnapshotBlockChunkDescriptor(
				second.index(), 2, 2, second.blockCount(), second.compression(),
				second.compressedByteCount(), second.compressedContentHash(),
				second.uncompressedByteCount(), second.uncompressedContentHash()));
		Fixture invalid = fixture.withManifest(new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION,
				fixture.archiveManifest().stateManifestSigningHash(), descriptors));

		assertFailure(invalid, "not exactly height-contiguous");
	}

	private void assertFailure(Fixture fixture, String message) {
		CheckpointSnapshotVerifier stateVerifier = mock(CheckpointSnapshotVerifier.class);
		CoreSnapshotArchiveVerifier verifier = verifier(stateVerifier, fixture.stateResult());
		SnapshotChunkSource unusedStateSource = ignored -> null;
		assertThatThrownBy(() -> verifier.verify(
				fixture.archiveManifest(), fixture.stateManifest(), unusedStateSource, fixture.chunkSource()))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining(message);
	}

	private CoreSnapshotArchiveVerifier verifier(
			CheckpointSnapshotVerifier stateVerifier,
			CheckpointSnapshotVerifier.VerificationResult stateResult) {
		CoreSnapshotEntityIndexVerifier entityVerifier = mock(CoreSnapshotEntityIndexVerifier.class);
		when(entityVerifier.verify(any(), any(), any(), any()))
				.thenReturn(new CoreSnapshotEntityIndexVerifier.VerificationResult(Map.of(), 0, 0, 0));
		when(stateVerifier.verifyWithFullHistoryAnchor(
				any(CheckpointSnapshotManifest.class), any(SnapshotChunkSource.class),
				any(VerifiedCoreArchiveHistory.class), any(CheckpointStateSupplementVerifier.class)))
				.thenAnswer(invocation -> {
					CheckpointStateSupplementVerifier supplement = invocation.getArgument(3);
					supplement.verify(CHECKPOINT_STATE_ROOT, mock(NodeLoader.class));
					return stateResult;
				});
		return new CoreSnapshotArchiveVerifier(stateVerifier, entityVerifier);
	}

	private Fixture fixture(List<StoredBlock> blocks, List<Integer> chunkSizes) {
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("fixture requires blocks");
		}
		StoredBlock checkpoint = blocks.getLast();
		StoredChainIdentity chainIdentity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 1, "archive-test",
				blocks.getFirst().getHash().toHexString(), null);
		List<SnapshotHeader> headers = new ArrayList<>();
		for (StoredBlock block : blocks) {
			headers.add(new SnapshotHeader(
					block.getHash(), block.getBlock().getHeader(), block.getCumulativeDifficulty()));
		}
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				CheckpointSnapshotLimits.FORMAT_VERSION, 1, chainIdentity, blocks.size() - 1L,
				checkpoint.getHash(), checkpoint.getBlock().getHeader().getStateRootHash(),
				checkpoint.getCumulativeDifficulty(),
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, headers), List.of());
		Hash stateSigningHash = CheckpointSnapshotManifestCodec.signingHash(stateManifest);

		Map<Integer, Bytes> chunks = new LinkedHashMap<>();
		List<CoreSnapshotBlockChunkDescriptor> descriptors = new ArrayList<>();
		int blockOffset = 0;
		for (int index = 0; index < chunkSizes.size(); index++) {
			int count = chunkSizes.get(index);
			List<StoredBlock> chunkBlocks = blocks.subList(blockOffset, blockOffset + count);
			Bytes uncompressed = CoreSnapshotBlockChunkCodec.encodeChunk(index, chunkBlocks);
			Bytes compressed = compress(uncompressed);
			chunks.put(index, compressed);
			descriptors.add(new CoreSnapshotBlockChunkDescriptor(
					index, blockOffset, blockOffset + count - 1L, count,
					CoreSnapshotChunkCompression.ZSTD,
					compressed.size(), Hash.hash(compressed),
					uncompressed.size(), Hash.hash(uncompressed)));
			blockOffset += count;
		}
		if (blockOffset != blocks.size()) {
			throw new IllegalArgumentException("fixture chunks do not consume all blocks");
		}
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION, stateSigningHash, descriptors);
		CheckpointSnapshotVerifier.VerificationResult stateResult =
				new CheckpointSnapshotVerifier.VerificationResult(
						stateManifest.checkpointHeight(), stateManifest.checkpointHash(),
						stateManifest.checkpointStateRoot(), 1, 1, stateSigningHash);
		return new Fixture(stateManifest, stateResult, archiveManifest, chunks);
	}

	private List<StoredBlock> chain(int blockCount) {
		List<StoredBlock> blocks = new ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (int height = 0; height < blockCount; height++) {
			cumulative = cumulative.add(BigInteger.valueOf(height + 1L));
			StoredBlock block = storedBlock(
					height, previous, height == blockCount - 1 ? CHECKPOINT_STATE_ROOT : Hash.ZERO,
					cumulative, Hash.ZERO);
			blocks.add(block);
			previous = block.getHash();
		}
		return List.copyOf(blocks);
	}

	private StoredBlock storedBlock(
			long height, Hash previous, Hash stateRoot, BigInteger cumulativeDifficulty, Hash txRoot) {
		BigInteger difficulty = BigInteger.valueOf(height + 1);
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(Instant.ofEpochSecond(1_800_000_000L + height))
				.previousHash(previous)
				.txRootHash(txRoot)
				.stateRootHash(stateRoot)
				.difficulty(difficulty)
				.coinbase(Address.ZERO)
				.nonce(height)
				.build();
		BlockHeader header = unsigned.toBuilder()
				.signature(SIGNER.sign(BlockHeaderUtil.hashForSigning(unsigned)))
				.build();
		Block block = BlockImpl.builder().header(header).txs(List.of()).build();
		return StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(cumulativeDifficulty)
				.receivedAt(header.getTimestamp())
				.receivedFrom(Address.ZERO)
				.connectedSource(height == 0 ? ConnectedSource.GENESIS : ConnectedSource.SYNC)
				.identity(header.getIdentity())
				.computeIndexes()
				.build();
	}

	private Fixture withReplacedPayloadAndDescriptor(Fixture base, Bytes payload) {
		CoreSnapshotBlockChunkDescriptor original = base.archiveManifest().blockChunks().getFirst();
		CoreSnapshotBlockChunkDescriptor replacement = new CoreSnapshotBlockChunkDescriptor(
				original.index(), original.firstHeight(), original.lastHeight(), original.blockCount(),
				original.compression(), payload.size(), Hash.hash(payload),
				original.uncompressedByteCount(), original.uncompressedContentHash());
		CoreSnapshotArchiveManifest manifest = new CoreSnapshotArchiveManifest(
				base.archiveManifest().formatVersion(), base.archiveManifest().stateManifestSigningHash(),
				List.of(replacement));
		return new Fixture(base.stateManifest(), base.stateResult(), manifest, Map.of(0, payload));
	}

	private Bytes compress(Bytes uncompressed) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			CoreSnapshotCompression.writeZstd(
					new ByteArrayInputStream(uncompressed.toArrayUnsafe()), output);
			return Bytes.wrap(output.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException("Cannot compress test archive chunk", e);
		}
	}

	private Fixture withCheckpointOnlyHeaderSegment(Fixture base, boolean wrongParentHash) {
		CheckpointSnapshotManifest source = base.stateManifest();
		SnapshotHeader parent = source.headerSegment().headers().get(1);
		SnapshotHeader checkpoint = source.headerSegment().headers().getLast();
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				source.formatVersion(), source.networkCode(), source.chainIdentity(), source.checkpointHeight(),
				source.checkpointHash(), source.checkpointStateRoot(), source.checkpointCumulativeDifficulty(),
				new SnapshotHeaderSegment(
						wrongParentHash ? Hash.hash(Bytes.ofUnsignedInt(44)) : parent.declaredHash(),
						parent.cumulativeDifficulty(), List.of(checkpoint)), source.chunks());
		Hash stateSigningHash = CheckpointSnapshotManifestCodec.signingHash(stateManifest);
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				base.archiveManifest().formatVersion(), stateSigningHash,
				base.archiveManifest().blockChunks());
		CheckpointSnapshotVerifier.VerificationResult stateResult =
				new CheckpointSnapshotVerifier.VerificationResult(
						stateManifest.checkpointHeight(), stateManifest.checkpointHash(),
						stateManifest.checkpointStateRoot(), 1, 1, stateSigningHash);
		return new Fixture(stateManifest, stateResult, archiveManifest, base.chunks());
	}

	private record Fixture(
			CheckpointSnapshotManifest stateManifest,
			CheckpointSnapshotVerifier.VerificationResult stateResult,
			CoreSnapshotArchiveManifest archiveManifest,
			Map<Integer, Bytes> chunks) {

		private CoreSnapshotArchiveChunkSource chunkSource() {
			return descriptor -> new ByteArrayInputStream(chunks.get(descriptor.index()).toArrayUnsafe());
		}

		private Fixture withChunk(int index, Bytes payload) {
			Map<Integer, Bytes> replaced = new LinkedHashMap<>(chunks);
			replaced.put(index, payload);
			return new Fixture(stateManifest, stateResult, archiveManifest, Map.copyOf(replaced));
		}

		private Fixture withManifest(CoreSnapshotArchiveManifest manifest) {
			return new Fixture(stateManifest, stateResult, manifest, chunks);
		}
	}
}
