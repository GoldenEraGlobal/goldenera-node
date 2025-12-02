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
package global.goldenera.node.explorer.services.indexer.helpers;

import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerRevertLogCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerTokenCoreService;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.TokenRevertDto;
import global.goldenera.node.shared.consensus.state.StateDiff;
import global.goldenera.node.shared.consensus.state.TokenState;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerTokenHelperService {

	ExIndexerRevertLogCoreService revertLogCoreService;
	ExIndexerTokenCoreService tokenCoreService;

	public void processTokens(Block block, Map<Address, StateDiff<TokenState>> tokenDiffs) {
		if (tokenDiffs.isEmpty())
			return;

		List<Object[]> logBatch = new ArrayList<>();
		List<Address> upsertKeys = new ArrayList<>();

		tokenDiffs.forEach((address, diff) -> {
			upsertKeys.add(address);
			boolean isNewRecord = diff.getOldValue().getUpdatedAtBlockHeight() < 0;

			if (isNewRecord) {
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.TOKEN, OperationType.INSERT,
						address.toArray(), null, null);
			} else {
				TokenRevertDto dto = TokenRevertDto.from(
						diff.getOldValue().getTotalSupply(),
						diff.getOldValue().getUpdatedAtBlockHeight(),
						diff.getOldValue().getUpdatedAtTimestamp(),
						diff.getOldValue().getName(),
						diff.getOldValue().getSmallestUnitName(),
						diff.getOldValue().getLogoUrl(),
						diff.getOldValue().getWebsiteUrl(),
						diff.getOldValue().getUpdatedByTxHash(),
						diff.getOldValue().getVersion());
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.TOKEN, OperationType.UPDATE,
						address.toArray(), null,
						dto);
			}
		});

		revertLogCoreService.insertLogBatch(logBatch);
		tokenCoreService.bulkUpsertTokens(upsertKeys, tokenDiffs);
	}
}
