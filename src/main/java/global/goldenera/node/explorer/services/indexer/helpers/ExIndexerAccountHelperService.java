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
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerAccountCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerRevertLogCoreService;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.BalanceRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.NonceRevertDto;
import global.goldenera.node.shared.datatypes.BalanceKey;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerAccountHelperService {

	ExIndexerRevertLogCoreService revertLogCoreService;
	ExIndexerAccountCoreService accountCoreService;

	public void processBalances(Block block, Map<BalanceKey, StateDiff<AccountBalanceState>> balanceDiffs) {
		if (balanceDiffs.isEmpty())
			return;

		List<Object[]> logBatch = new ArrayList<>();
		List<BalanceKey> upsertKeys = new ArrayList<>();

		balanceDiffs.forEach((key, diff) -> {
			upsertKeys.add(key);
			boolean isNewRecord = !diff.getOldValue().exists();

			if (isNewRecord) {
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ACCOUNT_BALANCE, OperationType.INSERT,
						key.getAddress().toArray(), key.getTokenAddress().toArray(), null);
			} else {
				BalanceRevertDto dto = BalanceRevertDto.from(
						diff.getOldValue().getBalance(),
						diff.getOldValue().getUpdatedAtBlockHeight(),
						diff.getOldValue().getUpdatedAtTimestamp(),
						diff.getOldValue().getVersion());
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ACCOUNT_BALANCE, OperationType.UPDATE,
						key.getAddress().toArray(), key.getTokenAddress().toArray(), dto);
			}
		});

		revertLogCoreService.insertLogBatch(logBatch);
		accountCoreService.bulkUpsertBalances(upsertKeys, balanceDiffs);
	}

	public void processNonces(Block block, Map<Address, StateDiff<AccountNonceState>> nonceDiffs) {
		if (nonceDiffs.isEmpty())
			return;

		List<Object[]> logBatch = new ArrayList<>();
		List<Address> upsertKeys = new ArrayList<>();

		nonceDiffs.forEach((address, diff) -> {
			upsertKeys.add(address);
			boolean isNewRecord = diff.getOldValue().getUpdatedAtBlockHeight() < 0;

			if (isNewRecord) {
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ACCOUNT_NONCE, OperationType.INSERT,
						address.toArray(), null,
						null);
			} else {
				NonceRevertDto dto = NonceRevertDto.from(
						diff.getOldValue().getNonce(),
						diff.getOldValue().getUpdatedAtBlockHeight(),
						diff.getOldValue().getUpdatedAtTimestamp(),
						diff.getOldValue().getVersion());
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ACCOUNT_NONCE, OperationType.UPDATE,
						address.toArray(), null,
						dto);
			}
		});

		revertLogCoreService.insertLogBatch(logBatch);
		accountCoreService.bulkUpsertNonces(upsertKeys, nonceDiffs);
	}

}
