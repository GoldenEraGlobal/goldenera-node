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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.converters.AddressSetConverter;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerConsensusCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerRevertLogCoreService;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.AliasRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.AuthorityRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.BipRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.NetworkParamsRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.ValidatorRevertDto;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerConsensusHelperService {

	ExIndexerRevertLogCoreService revertLogCoreService;
	ExIndexerConsensusCoreService consensusCoreService;

	AddressSetConverter addressSetConverter = new AddressSetConverter();

	public void processBips(Block block, Map<Hash, StateDiff<BipState>> bipDiffs) {
		if (bipDiffs.isEmpty())
			return;

		List<Object[]> logBatch = new ArrayList<>();
		List<Hash> upsertKeys = new ArrayList<>();

		bipDiffs.forEach((hash, diff) -> {
			upsertKeys.add(hash);
			boolean isNewRecord = diff.getOldValue().getUpdatedAtBlockHeight() < 0;
			if (isNewRecord) {
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.BIP, OperationType.INSERT,
						hash.toArray(), null, null);
			} else {
				// Convert approvers and disapprovers separately
				byte[] approversBytes = addressSetConverter.convertToDatabaseColumn(diff.getOldValue().getApprovers());
				Bytes approvers = approversBytes != null ? Bytes.wrap(approversBytes) : Bytes.EMPTY;

				byte[] disapproversBytes = addressSetConverter
						.convertToDatabaseColumn(diff.getOldValue().getDisapprovers());
				Bytes disapprovers = disapproversBytes != null ? Bytes.wrap(disapproversBytes) : Bytes.EMPTY;

				BipRevertDto dto = BipRevertDto.from(
						diff.getOldValue().getStatus(),
						diff.getOldValue().getUpdatedAtBlockHeight(),
						diff.getOldValue().getUpdatedAtTimestamp(),
						diff.getOldValue().getUpdatedByTxHash(),
						approvers,
						disapprovers,
						diff.getOldValue().isActionExecuted(),
						diff.getOldValue().getExecutedAtTimestamp(),
						diff.getOldValue().getVersion());
				revertLogCoreService.addLogToBatch(logBatch, block, EntityType.BIP, OperationType.UPDATE,
						hash.toArray(), null, dto);
			}
		});
		revertLogCoreService.insertLogBatch(logBatch);
		consensusCoreService.bulkUpsertBips(upsertKeys, bipDiffs);
	}

	public void processNetworkParams(Block block, StateDiff<NetworkParamsState> diff) {
		if (diff == null)
			return;
		NetworkParamsRevertDto dto = NetworkParamsRevertDto.from(diff.getOldValue());
		List<Object[]> logBatch = new ArrayList<>();
		revertLogCoreService.addLogToBatch(logBatch, block, EntityType.NETWORK_PARAMS, OperationType.UPDATE,
				new byte[] { 1 }, null, dto);
		revertLogCoreService.insertLogBatch(logBatch);
		consensusCoreService.upsertNetworkParams(diff.getNewValue());
	}

	public void processAliases(
			Block block,
			Map<String, AddressAliasState> addressAliasesToAdd,
			Map<String, AddressAliasState> addressAliasesToRemove) {
		List<Object[]> logBatch = new ArrayList<>();
		addressAliasesToRemove.forEach((alias, oldState) -> {
			AliasRevertDto dto = AliasRevertDto.from(
					oldState.getAddress(),
					oldState.getOriginTxHash(),
					oldState.getCreatedAtBlockHeight(),
					oldState.getCreatedAtTimestamp(),
					oldState.getVersion());
			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ADDRESS_ALIAS, OperationType.DELETE,
					alias.getBytes(StandardCharsets.UTF_8), null, dto);
		});

		addressAliasesToAdd.forEach((alias, newState) -> {
			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.ADDRESS_ALIAS, OperationType.INSERT,
					alias.getBytes(StandardCharsets.UTF_8), null, null);
		});

		revertLogCoreService.insertLogBatch(logBatch);

		if (!addressAliasesToAdd.isEmpty()) {
			consensusCoreService.bulkUpsertAliases(addressAliasesToAdd);
		}
		if (!addressAliasesToRemove.isEmpty()) {
			consensusCoreService.bulkDeleteAliases(addressAliasesToRemove.keySet());
		}
	}

	public void processAuthorities(
			Block block,
			Map<Address, AuthorityState> authoritiesToAdd,
			Map<Address, AuthorityState> authoritiesToRemove) {
		List<Object[]> logBatch = new ArrayList<>();
		authoritiesToRemove.forEach((address, oldState) -> {
			AuthorityRevertDto dto = AuthorityRevertDto.from(
					oldState.getOriginTxHash(),
					oldState.getCreatedAtBlockHeight(),
					oldState.getCreatedAtTimestamp(),
					oldState.getVersion());

			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.AUTHORITY, OperationType.DELETE,
					address.toArray(), null, dto);
		});

		authoritiesToAdd.forEach((address, newState) -> {
			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.AUTHORITY, OperationType.INSERT,
					address.toArray(), null, null);
		});

		revertLogCoreService.insertLogBatch(logBatch);

		if (!authoritiesToAdd.isEmpty()) {
			consensusCoreService.bulkUpsertAuthorities(authoritiesToAdd);
		}
		if (!authoritiesToRemove.isEmpty()) {
			consensusCoreService.bulkDeleteAuthorities(authoritiesToRemove.keySet());
		}
	}

	public void processValidators(
			Block block,
			Map<Address, ValidatorState> validatorsToAdd,
			Map<Address, ValidatorState> validatorsToRemove) {
		List<Object[]> logBatch = new ArrayList<>();
		validatorsToRemove.forEach((address, oldState) -> {
			ValidatorRevertDto dto = ValidatorRevertDto.from(
					oldState.getOriginTxHash(),
					oldState.getCreatedAtBlockHeight(),
					oldState.getCreatedAtTimestamp(),
					oldState.getVersion());

			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.VALIDATOR, OperationType.DELETE,
					address.toArray(), null, dto);
		});

		validatorsToAdd.forEach((address, newState) -> {
			revertLogCoreService.addLogToBatch(logBatch, block, EntityType.VALIDATOR, OperationType.INSERT,
					address.toArray(), null, null);
		});

		revertLogCoreService.insertLogBatch(logBatch);

		if (!validatorsToAdd.isEmpty()) {
			consensusCoreService.bulkUpsertValidators(validatorsToAdd);
		}
		if (!validatorsToRemove.isEmpty()) {
			consensusCoreService.bulkDeleteValidators(validatorsToRemove.keySet());
		}
	}
}
