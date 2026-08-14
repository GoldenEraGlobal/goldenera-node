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
package global.goldenera.node.explorer.api.v1.validator.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.processing.ValidatorMiningViewService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.api.v1.validator.dtos.ValidatorDtoV1;
import global.goldenera.node.explorer.entities.ExValidator;
import global.goldenera.node.shared.enums.MiningPolicySource;

class ValidatorMapperCanonicalStateTest {

	private static final Address VALIDATOR =
			Address.fromHexString("0x1111111111111111111111111111111111111111");

	@Test
	void quotaAndEligibilityComeFromCanonicalWorldStateRatherThanStaleExplorerPolicy() {
		ExValidator staleExplorerRow = staleExplorerRow();
		ValidatorStateImpl canonicalValidator = explicitLimited(4_000);
		WorldState canonicalState = canonicalState(canonicalValidator, 40);
		ChainHeadStateCache stateCache = mock(ChainHeadStateCache.class);
		when(stateCache.getHeadSnapshot()).thenReturn(
				new HeadStateSnapshot(mock(StoredBlock.class), canonicalState));
		ValidatorMapper mapper = new ValidatorMapper(
				stateCache,
				new ValidatorMiningViewService(new ValidatorMiningPolicyService()));

		ValidatorDtoV1 dto = mapper.map(staleExplorerRow);

		assertThat(dto.getMaxMiningShareBps()).isEqualTo(4_000);
		assertThat(dto.getMiningLimitMode()).isEqualTo(MiningLimitMode.LIMITED);
		assertThat(dto.getMiningPolicySource()).isEqualTo(MiningPolicySource.EXPLICIT);
		assertThat(dto.getMaxBlocksInCurrentWindow()).isEqualTo(40);
		assertThat(dto.getBlocksMinedInCurrentWindow()).isEqualTo(40);
		assertThat(dto.getRemainingBlocksInCurrentWindow()).isZero();
		assertThat(dto.getMiningEligible()).isFalse();
	}

	private ExValidator staleExplorerRow() {
		ExValidator validator = new ExValidator();
		validator.setAddress(VALIDATOR);
		validator.setVersion(ValidatorStateVersion.V2);
		validator.setOriginTxHash(Hash.ZERO);
		validator.setCreatedAtBlockHeight(1);
		validator.setCreatedAtTimestamp(Instant.EPOCH);
		validator.setMiningLimitMode(MiningLimitMode.LIMITED);
		validator.setMiningPolicySource(MiningPolicySource.EXPLICIT);
		validator.setMaxMiningShareBpsValue(1_000L);
		validator.setPolicyUpdatedByTxHash(Hash.ZERO);
		validator.setPolicyUpdatedAtBlockHeightValue(2L);
		validator.setPolicyUpdatedAtTimestamp(Instant.EPOCH);
		return validator;
	}

	private WorldState canonicalState(ValidatorStateImpl validator, int minedBlocks) {
		WorldState state = mock(WorldState.class);
		when(state.getValidator(VALIDATOR)).thenReturn(validator);
		when(state.getParams()).thenReturn(NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.validatorMiningWindowBlocks(100)
				.currentValidatorCount(1)
				.currentUnlimitedValidatorCount(0)
				.build());
		MiningWindowStateImpl window = MiningWindowStateImpl.empty(100, 10);
		for (int index = 0; index < minedBlocks; index++) {
			window = window.append(VALIDATOR, 11 + index);
		}
		when(state.getMiningWindow()).thenReturn(window);
		return state;
	}

	private ValidatorStateImpl explicitLimited(long bps) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(Instant.EPOCH)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(bps)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(2)
				.policyUpdatedAtTimestamp(Instant.EPOCH)
				.build();
	}
}
