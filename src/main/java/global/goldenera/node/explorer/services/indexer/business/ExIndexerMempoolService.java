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
package global.goldenera.node.explorer.services.indexer.business;

import static global.goldenera.node.explorer.config.ExplorerAsyncConfig.EXPLORER_SCHEDULER;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.explorer.entities.ExMemTransfer;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerMempoolCoreService;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Value;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExIndexerMempoolService {
	private static final int MAX_FLUSH_ACTIONS = 1_000;

	GeneralProperties generalProperties;
	ExplorerRuntimeReadiness explorerReadiness;
	MeterRegistry registry;
	ExIndexerMempoolCoreService exMempoolCoreService;
	ThreadPoolTaskScheduler explorerScheduler;

	public ExIndexerMempoolService(GeneralProperties generalProperties, ExplorerRuntimeReadiness explorerReadiness,
			MeterRegistry registry,
			ExIndexerMempoolCoreService exMempoolCoreService,
			@Qualifier(EXPLORER_SCHEDULER) ThreadPoolTaskScheduler explorerScheduler) {
		this.generalProperties = generalProperties;
		this.explorerReadiness = explorerReadiness;
		this.registry = registry;
		this.exMempoolCoreService = exMempoolCoreService;
		this.explorerScheduler = explorerScheduler;
	}

	Map<Hash, PendingAction> buffer = new ConcurrentHashMap<>();

	private enum ActionType {
		ADD, REMOVE
	}

	@Value
	private static class PendingAction {
		ActionType type;
		MempoolEntry entry;
	}

	@PostConstruct
	public void init() {
		if (!enabled()) {
			return;
		}
		// Schedule the flushBuffer task to run every 3 seconds using
		// explorerTaskScheduler
		explorerScheduler.scheduleWithFixedDelay(
				this::flushBuffer,
				Duration.ofMillis(3000));
		log.info("ExMempoolService: Scheduled flushBuffer on explorerTaskScheduler every 3s");
		registry.gaugeMapSize("explorer.mempool.pending_actions", Tags.empty(), buffer);
	}

	// --------------------------------------------------------
	// LISTENERS
	// --------------------------------------------------------

	@Transactional(rollbackFor = Exception.class)
	public void resetOnCoreReady() {
		exMempoolCoreService.truncate();
	}

	@EventListener
	public void onMempoolAdd(MempoolTxAddEvent event) {
		if (!enabled()) {
			return;
		}
		buffer.put(event.getEntry().getTx().getHash(), new PendingAction(ActionType.ADD, event.getEntry()));
	}

	@EventListener
	public void onMempoolRemove(MempoolTxRemoveEvent event) {
		if (!enabled()) {
			return;
		}
		Hash txHash = event.getEntry().getTx().getHash();
		buffer.compute(txHash, (key, existingAction) -> {
			if (existingAction != null && existingAction.getType() == ActionType.ADD) {
				return null;
			}
			return new PendingAction(ActionType.REMOVE, null);
		});
	}

	// --------------------------------------------------------
	// FLUSHER
	// --------------------------------------------------------

	/**
	 * Flushes pending mempool changes to database.
	 * Scheduled via explorerTaskScheduler in init().
	 */
	public void flushBuffer() {
		if (!enabled()) {
			return;
		}
		if (buffer.isEmpty()) {
			return;
		}

		List<Hash> keysProcessing = buffer.keySet().stream().limit(MAX_FLUSH_ACTIONS).toList();

		if (keysProcessing.isEmpty())
			return;

		List<ExMemTransfer> toAdd = new ArrayList<>();
		List<Hash> toRemove = new ArrayList<>();
		Map<Hash, PendingAction> processing = new ConcurrentHashMap<>();

		for (Hash hash : keysProcessing) {
			PendingAction action = buffer.remove(hash);
			if (action != null) {
				processing.put(hash, action);
				if (action.getType() == ActionType.ADD) {
					MempoolEntry entry = action.getEntry();
					Tx tx = entry.getTx();
					ExMemTransfer memTransfer = ExMemTransfer.builder()
							.hash(tx.getHash())
							.addedAt(entry.getFirstSeenTime())
							.transferType(TransferType.resolveFromTx(tx))
							.from(tx.getSender())
							.to(tx.getRecipient())
							.tokenAddress(tx.getTokenAddress())
							.txType(tx.getType())
							.txTimestamp(tx.getTimestamp())
							.network(tx.getNetwork())
							.version(tx.getVersion())
							.amount(tx.getAmount())
							.fee(entry.getTx().getFee())
							.nonce(entry.getTx().getNonce())
							.size(entry.getTx().getSize())
							.signature(entry.getTx().getSignature())
							.referenceHash(entry.getTx().getReferenceHash())
							.message(entry.getTx().getMessage())
							.payload(entry.getTx().getPayload())
							.build();
					toAdd.add(memTransfer);
				} else {
					toRemove.add(hash);
				}
			}
		}

		int addedCount = 0;
		int removedCount = 0;

		try {
			exMempoolCoreService.applyBatch(toAdd, toRemove);
			addedCount = toAdd.size();
			removedCount = toRemove.size();
		} catch (RuntimeException failure) {
			processing.forEach(buffer::putIfAbsent);
			throw failure;
		}

		if (addedCount > 0 || removedCount > 0) {
			log.debug("Mempool Flushed: +{} / -{} txs", addedCount, removedCount);
		}

		if (addedCount > 0) {
			registry.summary("explorer.mempool.flush_add").record(addedCount);
		}
		if (removedCount > 0) {
			registry.summary("explorer.mempool.flush_remove").record(removedCount);
		}
	}

	private boolean enabled() {
		return generalProperties.isExplorerEnable() && explorerReadiness.isReady();
	}
}
