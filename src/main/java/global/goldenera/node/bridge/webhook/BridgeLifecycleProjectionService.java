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
package global.goldenera.node.bridge.webhook;

import java.util.Comparator;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleReason;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeLifecycleProjectionService {

	private final ChainQuery chainQuery;
	private final BridgeLifecycleCoordinator coordinator;
	private final BridgeReorgPendingGate reorgPendingGate;
	private final BridgeLifecycleProjectionCursorStore cursorStore;
	private final BridgeSubscriptionRepository subscriptionRepository;
	private final GeneralProperties generalProperties;

	@Transactional(rollbackFor = Exception.class)
	public void applyCanonicalGroup(List<LifecycleJournalEntry> entries) {
		List<LifecycleJournalEntry> ordered = entries.stream()
				.sorted(Comparator.comparingInt(LifecycleJournalEntry::groupOrdinal))
				.toList();
		LifecycleJournalEntry last = ordered.get(ordered.size() - 1);
		LifecycleJournalEntry commit = ordered.stream()
				.filter(entry -> entry.operation() == LifecycleJournalOperation.REORG_COMMIT)
				.findFirst()
				.orElse(null);
		if (!hasEligibleSubscription(
				LifecycleJournalStream.CANONICAL, last, commit == null ? last.height() : null)) {
			cursorStore.advance(LifecycleJournalStream.CANONICAL, last.epoch(), lastSequence(ordered));
			return;
		}

		if (commit != null) {
			StoredBlock oldHead = storedBlock(commit.relatedHash());
			coordinator.reorg(
					oldHead.getHeight(), commit.relatedHash(), commit.height(), commit.primaryHash(), position(commit));
		}

		for (LifecycleJournalEntry entry : ordered) {
			if (entry.operation() == LifecycleJournalOperation.DISCONNECT) {
				Block orphanBlock = storedBlock(entry.primaryHash()).getBlock();
				coordinator.revertedBlock(orphanBlock, position(entry));
				reorgPendingGate.canonicalRevertCommitted(orphanBlock, entry.sequence());
			}
		}
		for (LifecycleJournalEntry entry : ordered) {
			if (entry.operation() == LifecycleJournalOperation.CONNECT && entry.height() > 0L) {
				StoredBlock storedBlock = storedBlock(entry.primaryHash());
				coordinator.confirmedBlock(storedBlock.getBlock(), storedBlock.getEvents(), position(entry));
			}
		}
		cursorStore.advance(LifecycleJournalStream.CANONICAL, ordered.get(0).epoch(), lastSequence(ordered));
	}

	@Transactional(rollbackFor = Exception.class)
	public void applyMempool(LifecycleJournalEntry entry) {
		if (!hasEligibleSubscription(LifecycleJournalStream.MEMPOOL, entry, null)) {
			if (entry.operation() == LifecycleJournalOperation.REORG_READD) {
				reorgPendingGate.discard(entry.primaryHash());
			}
			cursorStore.advance(LifecycleJournalStream.MEMPOOL, entry.epoch(), entry.sequence());
			return;
		}
		if (entry.operation() == LifecycleJournalOperation.PENDING
				&& reorgPendingGate.hasCanonicalRevert(entry.primaryHash())) {
			cursorStore.advance(LifecycleJournalStream.MEMPOOL, entry.epoch(), entry.sequence());
			return;
		}
		if ((entry.operation() == LifecycleJournalOperation.PENDING
				|| entry.operation() == LifecycleJournalOperation.REORG_READD)
				&& chainQuery.getTransactionBlock(entry.primaryHash()).isPresent()) {
			if (entry.operation() == LifecycleJournalOperation.REORG_READD) {
				reorgPendingGate.discard(entry.primaryHash());
			}
			cursorStore.advance(LifecycleJournalStream.MEMPOOL, entry.epoch(), entry.sequence());
			return;
		}
		Tx tx = TxDecoder.INSTANCE.decode(Bytes.wrap(entry.payload()));
		if (!tx.getHash().equals(entry.primaryHash())) {
			throw new GEFailedException("Lifecycle journal transaction payload hash mismatch");
		}
		MempoolEntry mempoolEntry = new MempoolEntry(tx, entry.occurredAt(), entry.height(), null);
		switch (entry.operation()) {
			case PENDING -> coordinator.pending(mempoolEntry, reason(entry.reasonCode()), position(entry));
			case REORG_READD -> reorgPendingGate.coreReadded(mempoolEntry, position(entry));
			case REPLACED -> coordinator.replaced(
					mempoolEntry, entry.relatedHash(), reason(entry.reasonCode()), position(entry));
			case DROPPED -> coordinator.dropped(mempoolEntry, reason(entry.reasonCode()), position(entry));
			default -> throw new GEFailedException("Unsupported mempool lifecycle operation " + entry.operation());
		}
		cursorStore.advance(LifecycleJournalStream.MEMPOOL, entry.epoch(), entry.sequence());
	}

	private StoredBlock storedBlock(Hash hash) {
		if (hash == null) {
			throw new GEFailedException("Lifecycle journal block hash is missing");
		}
		return chainQuery.getStoredBlockByHash(hash)
				.orElseThrow(() -> new GEFailedException("Lifecycle journal block data is unavailable: " + hash));
	}

	private long lastSequence(List<LifecycleJournalEntry> entries) {
		return entries.stream().mapToLong(LifecycleJournalEntry::sequence).max().orElseThrow();
	}

	private String reason(int code) {
		return MempoolLifecycleReason.fromCode(code).name();
	}

	private BridgeSourcePosition position(LifecycleJournalEntry entry) {
		return new BridgeSourcePosition(entry.epoch(), entry.sequence(), entry.eventKey());
	}

	private boolean hasEligibleSubscription(
			LifecycleJournalStream stream, LifecycleJournalEntry entry, Long canonicalHeight) {
		return subscriptionRepository.existsEnabledForSource(
				generalProperties.getNetwork(), stream.code(), entry.epoch(), entry.sequence(), canonicalHeight);
	}
}
