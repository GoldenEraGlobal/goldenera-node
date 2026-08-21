/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.FORMAT_VERSION;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_BLOCKS_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_CHUNK_COUNT;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_TOTAL_BLOCKS;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;

/**
 * Offline FULL CORE verifier. It first streams every canonical StoredBlock from
 * genesis through the checkpoint, uses that verified history as the work anchor
 * for isolated checkpoint-state verification, and only then returns an
 * activation capability. It does not open or mutate the production database.
 */
public final class CoreSnapshotArchiveVerifier {

	private final CheckpointSnapshotVerifier stateVerifier;

	public CoreSnapshotArchiveVerifier(CheckpointSnapshotVerifier stateVerifier) {
		this.stateVerifier = Objects.requireNonNull(stateVerifier, "stateVerifier");
	}

	public VerifiedCoreSnapshotArchive verify(
			CoreSnapshotArchiveManifest archiveManifest,
			CheckpointSnapshotManifest stateManifest,
			SnapshotChunkSource stateChunkSource,
			CoreSnapshotArchiveChunkSource blockChunkSource) {
		Objects.requireNonNull(stateManifest, "stateManifest");
		Objects.requireNonNull(stateChunkSource, "stateChunkSource");
		Objects.requireNonNull(archiveManifest, "archiveManifest");
		Objects.requireNonNull(blockChunkSource, "blockChunkSource");

		Hash expectedStateSigningHash = CheckpointSnapshotManifestCodec.signingHash(stateManifest);
		verifyArchiveManifestBinding(archiveManifest, expectedStateSigningHash);
		DescriptorTotals totals = verifyDescriptors(archiveManifest.blockChunks(), stateManifest.checkpointHeight());
		HistoryVerification history = verifyHistory(
				archiveManifest, stateManifest, blockChunkSource, totals);
		CheckpointSnapshotVerifier.VerificationResult stateResult = stateVerifier.verifyWithFullHistoryAnchor(
				stateManifest, stateChunkSource, history.fullHistory());
		verifyStateResultBinding(stateManifest, stateResult, expectedStateSigningHash);

		return new VerifiedCoreSnapshotArchive(
				stateManifest.checkpointHeight(), stateManifest.checkpointHash(),
				stateManifest.checkpointStateRoot(), stateManifest.checkpointCumulativeDifficulty(),
				expectedStateSigningHash, CoreSnapshotArchiveManifestCodec.signingHash(archiveManifest),
				history.blockCount(), archiveManifest.blockChunks().size(), history.encodedBytes());
	}

	private HistoryVerification verifyHistory(
			CoreSnapshotArchiveManifest archiveManifest,
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveChunkSource blockChunkSource,
			DescriptorTotals totals) {
		if (stateManifest.headerSegment().headers().isEmpty()) {
			throw failure("State manifest header segment is empty");
		}
		long segmentStartHeight = stateManifest.headerSegment().headers().getFirst().header().getHeight();
		long anchorHeight = segmentStartHeight - 1;
		Hash anchorHash = segmentStartHeight == 0 ? Hash.ZERO : null;
		BigInteger anchorDifficulty = segmentStartHeight == 0 ? BigInteger.ZERO : null;

		long expectedHeight = 0;
		Hash previousHash = null;
		BigInteger cumulativeDifficulty = BigInteger.ZERO;
		long streamedBytes = 0;
		for (CoreSnapshotBlockChunkDescriptor descriptor : archiveManifest.blockChunks()) {
			Digest256 digest = new Digest256();
			long chunkBytes;
			try (InputStream opened = Objects.requireNonNull(
					blockChunkSource.open(descriptor), "Archive chunk source returned null")) {
				CountingDigestInputStream counted = new CountingDigestInputStream(
						opened, digest, descriptor.byteCount());
				try (counted; CoreSnapshotBlockChunkCodec.Reader reader =
						CoreSnapshotBlockChunkCodec.open(counted, descriptor)) {
					while (reader.hasNext()) {
						StoredBlock storedBlock = reader.next();
						BlockVerification verified = verifyBlock(
								storedBlock, stateManifest, expectedHeight, previousHash, cumulativeDifficulty);
						expectedHeight++;
						previousHash = verified.hash();
						cumulativeDifficulty = verified.cumulativeDifficulty();
						if (verified.height() == anchorHeight) {
							anchorHash = verified.hash();
							anchorDifficulty = verified.cumulativeDifficulty();
						}
					}
					reader.finish();
				}
				chunkBytes = counted.count();
			} catch (SnapshotVerificationException e) {
				throw e;
			} catch (Exception e) {
				throw failure("Cannot read archive block chunk " + descriptor.index(), e);
			}
			if (chunkBytes != descriptor.byteCount()
					|| !Hash.wrap(digest.digest()).equals(descriptor.contentHash())) {
				throw failure("Archive block chunk integrity mismatch: " + descriptor.index());
			}
			streamedBytes = addExact(streamedBytes, chunkBytes, "Archive byte count overflow");
		}

		if (expectedHeight != totals.blockCount()
				|| expectedHeight != stateManifest.checkpointHeight() + 1) {
			throw failure("Archive block stream is truncated");
		}
		if (!Objects.equals(previousHash, stateManifest.checkpointHash())
				|| !cumulativeDifficulty.equals(stateManifest.checkpointCumulativeDifficulty())) {
			throw failure("Archive final checkpoint hash/cumulative difficulty mismatch");
		}
		if (streamedBytes != totals.byteCount()) {
			throw failure("Archive total byte count mismatch");
		}
		if (anchorHash == null || anchorDifficulty == null
				|| !anchorHash.equals(stateManifest.headerSegment().parentHash())
				|| !anchorDifficulty.equals(stateManifest.headerSegment().parentCumulativeDifficulty())) {
			throw failure("Full archive does not prove the state header segment work anchor");
		}
		VerifiedCoreArchiveHistory fullHistory = new VerifiedCoreArchiveHistory(
				stateManifest.checkpointHeight(), stateManifest.checkpointHash(),
				stateManifest.checkpointCumulativeDifficulty(), anchorHeight, anchorHash, anchorDifficulty);
		return new HistoryVerification(fullHistory, expectedHeight, streamedBytes);
	}

	private void verifyArchiveManifestBinding(
			CoreSnapshotArchiveManifest archiveManifest, Hash expectedStateSigningHash) {
		if (archiveManifest.formatVersion() != FORMAT_VERSION) {
			throw failure("Unsupported core snapshot archive format: " + archiveManifest.formatVersion());
		}
		if (!archiveManifest.stateManifestSigningHash().equals(expectedStateSigningHash)) {
			throw failure("Core archive is not bound to the verified state manifest");
		}
	}

	private void verifyStateResultBinding(
			CheckpointSnapshotManifest stateManifest,
			CheckpointSnapshotVerifier.VerificationResult stateResult,
			Hash expectedStateSigningHash) {
		if (stateResult == null || !stateResult.manifestSigningHash().equals(expectedStateSigningHash)) {
			throw failure("State verification result is not bound to the state manifest");
		}
		if (stateResult.checkpointHeight() != stateManifest.checkpointHeight()
				|| !stateResult.checkpointHash().equals(stateManifest.checkpointHash())
				|| !stateResult.stateRoot().equals(stateManifest.checkpointStateRoot())) {
			throw failure("State verification result does not match its checkpoint manifest");
		}
	}

	private DescriptorTotals verifyDescriptors(
			List<CoreSnapshotBlockChunkDescriptor> descriptors, long checkpointHeight) {
		if (checkpointHeight < 0 || checkpointHeight == Long.MAX_VALUE
				|| checkpointHeight + 1 > MAX_TOTAL_BLOCKS) {
			throw failure("Checkpoint height exceeds full archive limits");
		}
		if (descriptors.isEmpty() || descriptors.size() > MAX_CHUNK_COUNT) {
			throw failure("Full archive must declare 1.." + MAX_CHUNK_COUNT + " block chunks");
		}
		long nextHeight = 0;
		long totalBlocks = 0;
		long totalBytes = 0;
		for (int index = 0; index < descriptors.size(); index++) {
			CoreSnapshotBlockChunkDescriptor descriptor = descriptors.get(index);
			if (descriptor.index() != index || descriptor.blockCount() <= 0
					|| descriptor.blockCount() > MAX_BLOCKS_PER_CHUNK
					|| descriptor.byteCount() <= 0 || descriptor.byteCount() > MAX_CHUNK_BYTES) {
				throw failure("Invalid archive block chunk descriptor: " + descriptor.index());
			}
			long expectedLast;
			try {
				expectedLast = Math.addExact(descriptor.firstHeight(), descriptor.blockCount() - 1L);
			} catch (ArithmeticException e) {
				throw failure("Archive descriptor height overflow", e);
			}
			if (descriptor.firstHeight() != nextHeight || descriptor.lastHeight() != expectedLast) {
				throw failure("Archive chunk ranges are not exactly height-contiguous at chunk " + index);
			}
			nextHeight = addExact(expectedLast, 1, "Archive height overflow");
			totalBlocks = addExact(totalBlocks, descriptor.blockCount(), "Archive block count overflow");
			totalBytes = addExact(totalBytes, descriptor.byteCount(), "Archive byte count overflow");
			if (totalBlocks > MAX_TOTAL_BLOCKS || totalBytes > MAX_TOTAL_BYTES) {
				throw failure("Full archive exceeds total block/byte limits");
			}
		}
		if (totalBlocks != checkpointHeight + 1 || nextHeight != checkpointHeight + 1) {
			throw failure("Archive descriptors do not cover genesis through the checkpoint");
		}
		return new DescriptorTotals(totalBlocks, totalBytes);
	}

	private BlockVerification verifyBlock(
			StoredBlock storedBlock,
			CheckpointSnapshotManifest stateManifest,
			long expectedHeight,
			Hash previousHash,
			BigInteger parentCumulativeDifficulty) {
		try {
			if (storedBlock == null || storedBlock.isPartial() || storedBlock.getBlock() == null) {
				throw failure("Archive contains a partial or missing StoredBlock at height " + expectedHeight);
			}
			Block block = storedBlock.getBlock();
			BlockHeader header = block.getHeader();
			List<Tx> transactions = block.getTxs();
			if (header == null || transactions == null || block.getHeight() != expectedHeight
					|| header.getHeight() != expectedHeight) {
				throw failure("Archive block height/body mismatch at expected height " + expectedHeight);
			}
			Hash calculatedHash = block.getHash();
			if (!calculatedHash.equals(header.getHash()) || !calculatedHash.equals(storedBlock.getHash())) {
				throw failure("StoredBlock hash mismatch at height " + expectedHeight);
			}
			if (expectedHeight == 0) {
				if (!Hash.ZERO.equals(header.getPreviousHash())
						|| !stateManifest.chainIdentity().genesisHash().equals(calculatedHash.toHexString())) {
					throw failure("Archive genesis identity/previous hash mismatch");
				}
			} else if (!Objects.equals(header.getPreviousHash(), previousHash)) {
				throw failure("Archive previous-hash link mismatch at height " + expectedHeight);
			}
			if (header.getDifficulty() == null || header.getDifficulty().signum() <= 0) {
				throw failure("Archive block has non-positive difficulty at height " + expectedHeight);
			}
			BigInteger expectedCumulative = parentCumulativeDifficulty.add(header.getDifficulty());
			if (!expectedCumulative.equals(storedBlock.getCumulativeDifficulty())) {
				throw failure("StoredBlock cumulative difficulty mismatch at height " + expectedHeight);
			}
			Hash calculatedTxRoot = TxRootUtil.txRootHash(transactions);
			if (!calculatedTxRoot.equals(header.getTxRootHash())) {
				throw failure("Archive transaction root mismatch at height " + expectedHeight);
			}
			if (expectedHeight == stateManifest.checkpointHeight()
					&& (!calculatedHash.equals(stateManifest.checkpointHash())
							|| !header.getStateRootHash().equals(stateManifest.checkpointStateRoot())
							|| !expectedCumulative.equals(stateManifest.checkpointCumulativeDifficulty()))) {
				throw failure("Archive checkpoint block does not match state manifest");
			}
			verifyStoredMetadata(storedBlock, block, transactions, expectedHeight);
			return new BlockVerification(expectedHeight, calculatedHash, expectedCumulative);
		} catch (SnapshotVerificationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure("Cannot validate archived StoredBlock at height " + expectedHeight, e);
		}
	}

	private void verifyStoredMetadata(
			StoredBlock storedBlock, Block block, List<Tx> transactions, long height) {
		if (storedBlock.getBlockSize() != block.getSize()
				|| storedBlock.getTxCount() != transactions.size()
				|| !Objects.equals(storedBlock.getIdentity(), block.getHeader().getIdentity())) {
			throw failure("StoredBlock metadata mismatch at height " + height);
		}
		for (int index = 0; index < transactions.size(); index++) {
			Tx transaction = transactions.get(index);
			if (!Objects.equals(storedBlock.getTransactionHashByIndex(index), transaction.getHash())
					|| storedBlock.getTransactionSizeByIndex(index) != transaction.getSize()
					|| !Objects.equals(storedBlock.getTransactionSenderByIndex(index), transaction.getSender())) {
				throw failure("StoredBlock transaction index mismatch at height " + height);
			}
		}
		if (storedBlock.getTransactionHashes() == null
				|| storedBlock.getTransactionSizes() == null
				|| storedBlock.getTransactionSenders() == null
				|| storedBlock.getTransactionHashes().length != transactions.size()
				|| storedBlock.getTransactionSizes().length != transactions.size()
				|| storedBlock.getTransactionSenders().length != transactions.size()) {
			throw failure("StoredBlock transaction index cardinality mismatch at height " + height);
		}
	}

	private long addExact(long left, long right, String message) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException e) {
			throw failure(message, e);
		}
	}

	private SnapshotVerificationException failure(String message) {
		return new SnapshotVerificationException(message);
	}

	private SnapshotVerificationException failure(String message, Throwable cause) {
		return new SnapshotVerificationException(message, cause);
	}

	private record DescriptorTotals(long blockCount, long byteCount) {
	}

	private record BlockVerification(long height, Hash hash, BigInteger cumulativeDifficulty) {
	}

	private record HistoryVerification(
			VerifiedCoreArchiveHistory fullHistory, long blockCount, long encodedBytes) {
	}

	private static final class CountingDigestInputStream extends FilterInputStream {

		private final Digest256 digest;
		private final long maxBytes;
		private long count;

		private CountingDigestInputStream(InputStream input, Digest256 digest, long maxBytes) {
			super(input);
			this.digest = digest;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			if (count > maxBytes) {
				throw new IOException("Archive chunk exceeds its declared byte limit");
			}
			int value = super.read();
			if (value >= 0) {
				digest.update((byte) value);
				count++;
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (count > maxBytes) {
				throw new IOException("Archive chunk exceeds its declared byte limit");
			}
			long remainingWithTrailingProbe = maxBytes - count + 1;
			int boundedLength = (int) Math.min(length, remainingWithTrailingProbe);
			int read = super.read(bytes, offset, boundedLength);
			if (read > 0) {
				digest.update(bytes, offset, read);
				count += read;
			}
			return read;
		}

		private long count() {
			return count;
		}
	}
}
