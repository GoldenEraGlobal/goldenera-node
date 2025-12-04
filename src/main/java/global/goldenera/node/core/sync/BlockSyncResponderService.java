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

import static global.goldenera.node.core.config.CoreAsyncConfig.P2P_SEND_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockSyncResponderService {

	ChainQuery chainQueryService;

	@EventListener
	@Async(P2P_SEND_EXECUTOR)
	public void handleGetHeaders(P2PHeadersRequestedEvent event) {
		long start = System.currentTimeMillis();
		List<Hash> locators = event.getLocators();
		Hash stopHash = event.getStopHash();
		int batchSize = event.getBatchSize();
		List<BlockHeader> headersToSend = new ArrayList<>();

		long findAncestorStart = System.currentTimeMillis();
		Optional<Block> commonAncestorOpt = chainQueryService
				.findCommonAncestor(new LinkedHashSet<>(locators));
		long findAncestorTime = System.currentTimeMillis() - findAncestorStart;

		if (commonAncestorOpt.isPresent()) {
			Block ancestor = commonAncestorOpt.get();
			long startHeight = ancestor.getHeight() + 1;
			if (startHeight == 0)
				startHeight = 1;
			long limit = batchSize > 0 ? Math.min(batchSize, 2000) : 500;
			long endHeight = startHeight + limit;
			if (stopHash != null) {
				Optional<Block> stopBlock = chainQueryService.getBlockByHash(stopHash);
				if (stopBlock.isPresent()) {
					endHeight = Math.min(endHeight, stopBlock.get().getHeight());
				}
			}
			long myTipHeight = chainQueryService.getLatestBlockOrThrow().getHeight();
			endHeight = Math.min(endHeight, myTipHeight);

			long headersStart = System.currentTimeMillis();
			headersToSend = chainQueryService.findHeadersByHeightRange(startHeight, endHeight);
			long headersTime = System.currentTimeMillis() - headersStart;

			long totalTime = System.currentTimeMillis() - start;
			if (totalTime > 1000) { // Only log slow requests
				log.warn("SLOW GetHeaders: {} headers in {}ms (ancestor: {}ms, headers: {}ms) range [{}-{}]",
						headersToSend.size(), totalTime, findAncestorTime, headersTime, startHeight, endHeight);
			}
		}
		event.getPeer().sendBlockHeaders(headersToSend, event.getRequestId());
	}

	@EventListener
	@Async(P2P_SEND_EXECUTOR)
	public void handleGetBodies(P2PBlockBodiesRequestedEvent event) {
		List<Hash> hashes = event.getHashes();
		List<List<Tx>> bodies = new ArrayList<>();
		for (Hash blockHash : hashes) {
			Optional<Block> blockOpt = chainQueryService.getBlockByHash(blockHash);
			if (blockOpt.isPresent()) {
				bodies.add(blockOpt.get().getTxs());
			} else {
				bodies.add(new ArrayList<>());
			}
		}
		event.getPeer().sendBlockBodies(bodies, event.getRequestId());
	}
}