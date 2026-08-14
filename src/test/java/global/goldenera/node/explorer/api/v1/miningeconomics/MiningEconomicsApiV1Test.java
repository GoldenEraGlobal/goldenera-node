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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.api.v1.miningeconomics.dtos.MiningEconomicsSnapshotDtoV1;
import global.goldenera.node.explorer.api.v1.miningeconomics.MiningEconomicsIndexedSnapshotService.IndexedValidatorSnapshot;
import global.goldenera.node.explorer.api.v1.networkparams.dtos.NetworkParamsDtoV1;
import global.goldenera.node.explorer.api.v1.networkparams.mappers.NetworkParamsMapper;
import global.goldenera.node.explorer.api.v1.validator.dtos.ValidatorDtoV1;
import global.goldenera.node.explorer.api.v1.validator.mappers.ValidatorMapper;
import global.goldenera.node.explorer.entities.ExValidator;
import org.springframework.web.server.ResponseStatusException;

class MiningEconomicsApiV1Test {

	@Test
	void returnsNetworkAndValidatorsFromTheSameCanonicalHeadSnapshot() {
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		WorldState state = mock(WorldState.class);
		StoredBlock head = mock(StoredBlock.class);
		Hash headHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000001");
		when(head.getHeight()).thenReturn(42L);
		when(head.getHash()).thenReturn(headHash);
		when(cache.getHeadSnapshot()).thenReturn(new HeadStateSnapshot(head, state));
		NetworkParamsState params = mock(NetworkParamsState.class);
		when(state.getParams()).thenReturn(params);

		MiningEconomicsIndexedSnapshotService indexedSnapshotService = mock(MiningEconomicsIndexedSnapshotService.class);
		List<ExValidator> rows = List.of(mock(ExValidator.class));
		when(indexedSnapshotService.capture()).thenReturn(new IndexedValidatorSnapshot(42, headHash, rows));
		NetworkParamsMapper networkMapper = mock(NetworkParamsMapper.class);
		NetworkParamsDtoV1 networkDto = mock(NetworkParamsDtoV1.class);
		when(networkMapper.map(params)).thenReturn(networkDto);
		ValidatorMapper validatorMapper = mock(ValidatorMapper.class);
		List<ValidatorDtoV1> validatorDtos = List.of(mock(ValidatorDtoV1.class));
		when(validatorMapper.map(rows, state)).thenReturn(validatorDtos);
		MiningEconomicsApiV1 api = new MiningEconomicsApiV1(
				cache, indexedSnapshotService, networkMapper, validatorMapper);

		MiningEconomicsSnapshotDtoV1 result = api.snapshot();

		assertThat(result.headHeight()).isEqualTo(42);
		assertThat(result.headHash()).isEqualTo(headHash);
		assertThat(result.networkParams()).isSameAs(networkDto);
		assertThat(result.validators()).isSameAs(validatorDtos);
		verify(networkMapper).map(params);
		verify(validatorMapper).map(rows, state);
	}

	@Test
	void retriesAcrossCanonicalReorgAndReturnsOnlyTheMatchingIndexedSnapshot() {
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		WorldState stateA = mock(WorldState.class);
		WorldState stateB = mock(WorldState.class);
		Hash hashA = hash(1);
		Hash hashB = hash(2);
		StoredBlock headA = head(42, 1);
		StoredBlock headB = head(42, 2);
		when(cache.getHeadSnapshot()).thenReturn(
				new HeadStateSnapshot(headA, stateA),
				new HeadStateSnapshot(headB, stateB),
				new HeadStateSnapshot(headB, stateB),
				new HeadStateSnapshot(headB, stateB));
		NetworkParamsState paramsB = mock(NetworkParamsState.class);
		when(stateB.getParams()).thenReturn(paramsB);
		List<ExValidator> rowsA = List.of(mock(ExValidator.class));
		List<ExValidator> rowsB = List.of(mock(ExValidator.class));
		MiningEconomicsIndexedSnapshotService indexed = mock(MiningEconomicsIndexedSnapshotService.class);
		when(indexed.capture()).thenReturn(
				new IndexedValidatorSnapshot(42, hashA, rowsA),
				new IndexedValidatorSnapshot(42, hashB, rowsB));
		NetworkParamsMapper networkMapper = mock(NetworkParamsMapper.class);
		NetworkParamsDtoV1 networkDto = mock(NetworkParamsDtoV1.class);
		when(networkMapper.map(paramsB)).thenReturn(networkDto);
		ValidatorMapper validatorMapper = mock(ValidatorMapper.class);
		List<ValidatorDtoV1> validatorDtos = List.of(mock(ValidatorDtoV1.class));
		when(validatorMapper.map(rowsB, stateB)).thenReturn(validatorDtos);
		MiningEconomicsApiV1 api = new MiningEconomicsApiV1(cache, indexed, networkMapper, validatorMapper);

		MiningEconomicsSnapshotDtoV1 result = api.snapshot();

		assertThat(result.headHash()).isEqualTo(hashB);
		assertThat(result.validators()).isSameAs(validatorDtos);
		verify(validatorMapper, never()).map(rowsA, stateA);
	}

	@Test
	void failsClosedWhenExplorerValidatorIndexRemainsBehindCanonicalHead() {
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		WorldState state = mock(WorldState.class);
		StoredBlock canonicalHead = head(43, 3);
		when(cache.getHeadSnapshot()).thenReturn(new HeadStateSnapshot(canonicalHead, state));
		MiningEconomicsIndexedSnapshotService indexed = mock(MiningEconomicsIndexedSnapshotService.class);
		when(indexed.capture()).thenReturn(new IndexedValidatorSnapshot(42, hash(2), List.of()));
		ValidatorMapper validatorMapper = mock(ValidatorMapper.class);
		MiningEconomicsApiV1 api = new MiningEconomicsApiV1(
				cache, indexed, mock(NetworkParamsMapper.class), validatorMapper);

		assertThatThrownBy(api::snapshot)
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("503 SERVICE_UNAVAILABLE");
		verify(validatorMapper, never()).map(anyList(), any());
	}

	private StoredBlock head(long height, int hashValue) {
		StoredBlock head = mock(StoredBlock.class);
		when(head.getHeight()).thenReturn(height);
		when(head.getHash()).thenReturn(hash(hashValue));
		return head;
	}

	private Hash hash(int value) {
		return Hash.fromHexString(String.format("0x%064x", value));
	}
}
