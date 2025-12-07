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
package global.goldenera.node.core.webhook.business;

import static lombok.AccessLevel.PRIVATE;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.enums.WebhookTxStatus;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Listens for core blockchain application events and forwards them
 * to the dispatch service for filtering and queuing.
 * Runs asynchronously to avoid blocking blockchain operations.
 */
@Slf4j
@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
public class WebhookListenerService {

	WebhookDispatchService webhookDispatchService;

	/**
	 * Handles new blocks.
	 * Sends NEW_BLOCK and CONFIRMED for each transaction.
	 * This is correct regardless of the source (MINER, SYNC, REORG).
	 * If it's in a block, it's confirmed.
	 */
	@EventListener
	public void handleBlockConnected(BlockConnectedEvent event) {
		log.debug("Processing BlockConnectedEvent: {}", event.getBlock().getHash());
		webhookDispatchService.processNewBlockEvent(event.getBlock());
		int index = 0;
		for (Tx tx : event.getBlock().getTxs()) {
			webhookDispatchService.processAddressActivityEvent(event.getBlock(), tx,
					WebhookTxStatus.CONFIRMED, index++);
		}
	}

	/**
	 * Handles transactions entering the mempool.
	 * This is the key logic for PENDING vs. REVERTED.
	 */
	@EventListener
	public void handleMempoolTxAdd(MempoolTxAddEvent event) {
		log.trace("Processing MempoolTxAddEvent: {}", event.getEntry().getTx().getHash());
		switch (event.getReason()) {
			case NEW:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.PENDING, null);
				break;
			case REORG:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.REVERTED, null);
				break;
		}
	}

	/**
	 * Handles transactions being removed from the mempool.
	 */
	@EventListener
	public void handleMempoolTxRemove(MempoolTxRemoveEvent event) {
		log.trace("Processing MempoolTxRemoveEvent: {}", event.getEntry().getHash());

		switch (event.getReason()) {
			case MINED:
				break;
			case RBF:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.REPLACED, null);
				break;
			case STALE_NONCE:
			case EXPIRED:
			case INVALID:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.DROPPED, null);
				break;
		}
	}
}