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
package global.goldenera.node.core.sync;

import static global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource.SYNC;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Safe fallback activation: replay a fully verified archive through the normal
 * consensus execution and atomic sync-batch persistence path.
 */
@Service
@Slf4j
public class CoreSnapshotArchiveReplayer {

	static final int MAX_REPLAY_BATCH_BLOCKS = 250;
	static final long MAX_REPLAY_BATCH_BYTES = 128L * 1024 * 1024;
	private final ChainQuery chainQuery;
	private final BlockValidator blockValidator;
	private final BlockReorgs blockReorgs;

	public CoreSnapshotArchiveReplayer(
			ChainQuery chainQuery,
			BlockValidator blockValidator,
			BlockReorgs blockReorgs) {
		this.chainQuery = chainQuery;
		this.blockValidator = blockValidator;
		this.blockReorgs = blockReorgs;
	}

	public ReplayResult replay(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified) throws Exception {
		if (staged == null || verified == null || !verified.activationEligible()
				|| staged.stateSnapshot().domainManifest().checkpointHeight() != verified.checkpointHeight()
				|| !staged.stateSnapshot().domainManifest().checkpointHash().equals(verified.checkpointHash())
				|| !staged.stateSnapshot().domainManifest().checkpointStateRoot()
						.equals(verified.checkpointStateRoot())
				|| !staged.stateSnapshot().domainManifest().checkpointCumulativeDifficulty()
						.equals(verified.checkpointCumulativeDifficulty())
				|| !CheckpointSnapshotManifestCodec.signingHash(staged.stateSnapshot().domainManifest())
						.equals(verified.stateManifestSigningHash())
				|| !CoreSnapshotArchiveManifestCodec.signingHash(staged.archiveManifest())
						.equals(verified.archiveManifestSigningHash())
				|| staged.archiveManifest().blockChunks().size() != verified.chunkCount()
				|| archiveBlockCount(staged) != verified.blockCount()
				|| archiveEncodedBytes(staged) != verified.encodedBytes()) {
			throw new IllegalArgumentException("Replay requires the matching fully verified archive capability");
		}

		StoredBlock localHead = chainQuery.getLatestStoredBlockOrThrow();
		if (localHead.getHeight() > verified.checkpointHeight()) {
			throw new IllegalStateException("Local chain advanced beyond the snapshot checkpoint");
		}
		List<StoredBlock> pending = new ArrayList<>(MAX_REPLAY_BATCH_BLOCKS);
		long pendingBytes = 0;
		long replayedBlocks = 0;
		for (int chunkIndex = 0; chunkIndex < staged.archiveManifest().blockChunks().size(); chunkIndex++) {
			CoreSnapshotBlockChunkDescriptor descriptor = staged.archiveManifest().blockChunks().get(chunkIndex);
			Path chunkFile = staged.blockChunkFiles().get(chunkIndex);
			try (InputStream opened = openArchiveChunk(chunkFile, descriptor);
					CoreSnapshotBlockChunkCodec.Reader reader =
							CoreSnapshotBlockChunkCodec.openCompressed(opened, descriptor)) {
				while (reader.hasNext()) {
					StoredBlock archived = reader.next();
					if (archived.getHeight() <= localHead.getHeight()) {
						assertAlreadyCanonical(archived);
						continue;
					}
					long blockBytes = Math.max(1L, archived.getBlockSize());
					if (shouldFlushReplayBatch(pending.size(), pendingBytes, blockBytes)) {
						localHead = persistReplayBatch(localHead, pending);
						replayedBlocks += pending.size();
						pending.clear();
						pendingBytes = 0;
					}
					if (archived.getHeight() != localHead.getHeight() + pending.size() + 1L) {
						throw new GEFailedException("Archive replay height is not contiguous at " + archived.getHeight());
					}
					pending.add(archived);
					pendingBytes = Math.addExact(pendingBytes, blockBytes);
					if (pending.size() >= MAX_REPLAY_BATCH_BLOCKS
							|| pendingBytes >= MAX_REPLAY_BATCH_BYTES) {
						localHead = persistReplayBatch(localHead, pending);
						replayedBlocks += pending.size();
						pending.clear();
						pendingBytes = 0;
					}
				}
				reader.finish();
			}
		}
		if (!pending.isEmpty()) {
			localHead = persistReplayBatch(localHead, pending);
			replayedBlocks += pending.size();
		}
		StoredBlock finalHead = chainQuery.getLatestStoredBlockOrThrow();
		if (finalHead.getHeight() != verified.checkpointHeight()
				|| !finalHead.getHash().equals(verified.checkpointHash())
				|| !finalHead.getBlock().getHeader().getStateRootHash().equals(verified.checkpointStateRoot())
				|| !finalHead.getCumulativeDifficulty().equals(verified.checkpointCumulativeDifficulty())) {
			throw new GEFailedException("Archive replay did not reach the exact verified checkpoint");
		}
		log.info("CORE SNAPSHOT: Replayed {} blocks to verified height {}",
				replayedBlocks, finalHead.getHeight());
		return new ReplayResult(replayedBlocks, 0, finalHead.getHeight(), finalHead.getHash());
	}

	static boolean shouldFlushReplayBatch(int pendingCount, long pendingBytes, long nextBlockBytes) {
		if (pendingCount < 0 || pendingBytes < 0 || nextBlockBytes <= 0) {
			throw new IllegalArgumentException("Replay batch accounting cannot be negative or zero");
		}
		return pendingCount > 0 && (pendingCount >= MAX_REPLAY_BATCH_BLOCKS
				|| nextBlockBytes > MAX_REPLAY_BATCH_BYTES - pendingBytes);
	}

	private StoredBlock persistReplayBatch(StoredBlock ancestor, List<StoredBlock> archivedBlocks) throws Exception {
		Map<Long, Hash> randomXContext = new HashMap<>(archivedBlocks.size());
		for (StoredBlock archived : archivedBlocks) {
			randomXContext.put(archived.getHeight(), archived.getHash());
		}
		Map<Long, Hash> immutableContext = Map.copyOf(randomXContext);
		List<ValidatedSyncBlock> validated = new ArrayList<>(archivedBlocks.size());
		for (StoredBlock archived : archivedBlocks) {
			Block archivedBlock = archived.getBlock();
			StatelessValidatedHeader headerProof =
					blockValidator.validateHeader(archivedBlock.getHeader(), immutableContext);
			StatelessValidatedBlock blockProof = blockValidator.validateBlockBody(archivedBlock, headerProof);
			StoredBlock replayBlock = replayStoredBlock(archived, blockProof);
			validated.add(new ValidatedSyncBlock(replayBlock, blockProof));
		}
		blockReorgs.executeAtomicReorgSwap(new ValidatedSyncBatch(ancestor, validated));
		StoredBlock committed = chainQuery.getLatestStoredBlockOrThrow();
		StoredBlock expected = validated.getLast().storedBlock();
		if (committed.getHeight() != expected.getHeight() || !committed.getHash().equals(expected.getHash())) {
			throw new GEFailedException("Atomic archive replay batch committed an unexpected head");
		}
		return committed;
	}

	static StoredBlock replayStoredBlock(StoredBlock archived, StatelessValidatedBlock blockProof) {
		Block immutableBlock = blockProof.block();
		return StoredBlock.builder()
				.block(immutableBlock)
				.cumulativeDifficulty(archived.getCumulativeDifficulty())
				.receivedAt(immutableBlock.getHeader().getTimestamp())
				.receivedFrom(Address.ZERO)
				.identity(immutableBlock.getHeader().getIdentity())
				.connectedSource(SYNC)
				.computeIndexes()
				.build();
	}

	private void assertAlreadyCanonical(StoredBlock archived) {
		StoredBlock canonical = chainQuery.getStoredBlockByHeight(archived.getHeight())
				.orElseThrow(() -> new GEFailedException(
						"Missing already-replayed canonical block at height " + archived.getHeight()));
		if (!canonical.getHash().equals(archived.getHash())
				|| !canonical.getCumulativeDifficulty().equals(archived.getCumulativeDifficulty())) {
			throw new GEFailedException("Local canonical history differs from verified archive at height "
					+ archived.getHeight());
		}
	}

	private InputStream openArchiveChunk(
			Path file, CoreSnapshotBlockChunkDescriptor descriptor) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(file) != descriptor.compressedByteCount()) {
			throw new GEFailedException("Staged archive block chunk size changed after verification");
		}
		return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
	}

	private long archiveBlockCount(StagedCoreSnapshotArchiveDownload staged) {
		long total = 0;
		for (CoreSnapshotBlockChunkDescriptor descriptor : staged.archiveManifest().blockChunks()) {
			total = Math.addExact(total, descriptor.blockCount());
		}
		return total;
	}

	private long archiveEncodedBytes(StagedCoreSnapshotArchiveDownload staged) {
		long total = 0;
		for (CoreSnapshotBlockChunkDescriptor descriptor : staged.archiveManifest().blockChunks()) {
			total = Math.addExact(total, descriptor.uncompressedByteCount());
		}
		return total;
	}

	public record ReplayResult(long replayedBlocks, long preloadedNodes, long headHeight, Hash headHash) {
	}

}
