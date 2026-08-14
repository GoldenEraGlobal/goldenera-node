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
package global.goldenera.node.explorer.api.v1.miningeconomics;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.entities.ExValidator;
import global.goldenera.node.explorer.services.core.ExValidatorCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MiningEconomicsIndexedSnapshotService {

	private final ExIndexerStatusCoreService statusCoreService;
	private final ExValidatorCoreService validatorCoreService;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
	public IndexedValidatorSnapshot capture() {
		ExStatus before = statusCoreService.getStatusOrThrow();
		List<ExValidator> validators = validatorCoreService.getAll();
		ExStatus after = statusCoreService.getStatusOrThrow();
		if (before.getSyncedBlockHeight() != after.getSyncedBlockHeight()
				|| !before.getSyncedBlockHash().equals(after.getSyncedBlockHash())) {
			throw new IllegalStateException("Explorer index changed while capturing validator snapshot");
		}
		return new IndexedValidatorSnapshot(
				before.getSyncedBlockHeight(), before.getSyncedBlockHash(), List.copyOf(validators));
	}

	public record IndexedValidatorSnapshot(long headHeight, Hash headHash, List<ExValidator> validators) {
		public boolean matches(StoredBlock head) {
			return headHeight == head.getHeight() && headHash.equals(head.getHash());
		}
	}
}
