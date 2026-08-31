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
package global.goldenera.node.explorer.services.indexer.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.AccountBalanceStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.BalanceRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.NetworkParamsRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.ValidatorRevertDto;

class ExIndexerRevertDtosTest {

	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void balanceSnapshotCarriesNonzeroPendingMiningRewardCancellation() {
		BalanceRevertDto dto = BalanceRevertDto.from(
				Wei.valueOf(70), Wei.valueOf(40), Wei.valueOf(30), 11, TIME,
				AccountBalanceStateVersion.V2);

		assertThat(dto.balance()).isEqualByComparingTo("70");
		assertThat(dto.lockedMiningReward()).isEqualByComparingTo("40");
		assertThat(dto.pendingMiningRewardCancellation()).isEqualByComparingTo("30");
		assertThat(dto.updatedAtBlockHeight()).isEqualTo(11);
		assertThat(dto.version()).isEqualTo(AccountBalanceStateVersion.V2.getCode());
	}

	@Test
	void legacyValidatorSnapshotKeepsPolicyColumnsNull() {
		ValidatorRevertDto dto = ValidatorRevertDto.from(ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V1)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.build());

		assertThat(dto.miningLimitMode()).isNull();
		assertThat(dto.miningPolicySource()).isNull();
		assertThat(dto.maxMiningShareBps()).isNull();
		assertThat(dto.policyUpdatedByTxHashHex()).isNull();
		assertThat(dto.policyUpdatedAtBlockHeight()).isNull();
		assertThat(dto.policyUpdatedAtTimestamp()).isNull();
	}

	@Test
	void explicitValidatorSnapshotCarriesPolicyAndAuditFields() {
		ValidatorRevertDto dto = ValidatorRevertDto.from(ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(2_500)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(9)
				.policyUpdatedAtTimestamp(TIME)
				.build());

		assertThat(dto.miningLimitMode()).isEqualTo("LIMITED");
		assertThat(dto.miningPolicySource()).isEqualTo("EXPLICIT");
		assertThat(dto.maxMiningShareBps()).isEqualTo(2_500);
		assertThat(dto.policyUpdatedByTxHashHex()).isEqualTo(Hash.ZERO.toHexString());
		assertThat(dto.policyUpdatedAtBlockHeight()).isEqualTo(9);
		assertThat(dto.policyUpdatedAtTimestamp()).isEqualTo(TIME);
	}

	@Test
	void networkSnapshotDistinguishesLegacyAbsenceFromExplicitV2Values() {
		NetworkParamsRevertDto legacy = NetworkParamsRevertDto.from(NetworkParamsStateImpl.ZERO);
		NetworkParamsRevertDto explicit = NetworkParamsRevertDto.from(NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.blockReward(NetworkParamsStateImpl.ZERO.getBlockReward())
				.blockRewardPoolAddress(NetworkParamsStateImpl.ZERO.getBlockRewardPoolAddress())
				.minDifficulty(NetworkParamsStateImpl.ZERO.getMinDifficulty())
				.minTxBaseFee(NetworkParamsStateImpl.ZERO.getMinTxBaseFee())
					.minTxByteFee(NetworkParamsStateImpl.ZERO.getMinTxByteFee())
					.updatedByTxHash(Hash.ZERO)
					.updatedAtTimestamp(TIME)
					.validatorMiningWindowBlocks(100)
					.miningRewardVestingBlocks(50)
					.currentValidatorCount(3)
				.currentUnlimitedValidatorCount(2)
				.build());

		assertThat(legacy.validatorMiningWindowBlocks()).isNull();
		assertThat(legacy.miningRewardVestingBlocks()).isNull();
		assertThat(legacy.currentUnlimitedValidatorCount()).isNull();
		assertThat(explicit.validatorMiningWindowBlocks()).isEqualTo(100);
		assertThat(explicit.miningRewardVestingBlocks()).isEqualTo(50);
		assertThat(explicit.currentUnlimitedValidatorCount()).isEqualTo(2);
	}
}
