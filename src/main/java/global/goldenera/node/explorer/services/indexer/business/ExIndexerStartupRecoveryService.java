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

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExBlockHeader;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExIndexerStartupRecoveryService {

	private final ExBlockHeaderRepository blockHeaderRepository;
	private final ExIndexerStatusCoreService statusService;
	private final ExIndexerRevertService revertService;
	private final ChainQuery chainQuery;

	@Transactional(rollbackFor = Exception.class)
	public long reconcileCanonicalHead() {
		long explorerHeight = validateAndRepairStatus();
		while (explorerHeight >= 0) {
			Hash explorerHash = statusService.getStatus().map(ExStatus::getSyncedBlockHash).orElse(null);
			StoredBlock canonical = chainQuery.getStoredBlockByHeight(explorerHeight).orElse(null);
			if (explorerHash != null && canonical != null && canonical.getHash().equals(explorerHash)) {
				return explorerHeight;
			}
			log.warn("Explorer detected a startup fork at block #{}. Reverting explorer head {}",
					explorerHeight, explorerHash);
			revertService.revertBlock(explorerHash, explorerHeight);
			explorerHeight--;
		}
		return -1L;
	}

	private long validateAndRepairStatus() {
		Optional<ExBlockHeader> realTopBlock = blockHeaderRepository.findLatest();
		if (realTopBlock.isEmpty()) {
			statusService.clearStatus();
			log.info("No blocks found in Explorer DB; startup catch-up begins at genesis");
			return -1L;
		}

		ExBlockHeader top = realTopBlock.get();
		ExStatus status = statusService.getStatus().orElse(null);
		boolean repair = status == null
				|| status.getSyncedBlockHeight() != top.getHeight()
				|| !status.getSyncedBlockHash().equals(top.getHash());
		if (repair) {
			statusService.updateStatus(top);
			log.warn("Repaired Explorer status to block #{} ({})", top.getHeight(), top.getHash());
		}
		return top.getHeight();
	}
}
