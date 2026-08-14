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
package global.goldenera.node.core.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.ValidatorMiningViewService.ValidatorMiningView;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.enums.MiningPolicySource;

class ValidatorMiningViewServiceTest {

	private static final Address VALIDATOR = Address.fromHexString("0x1111111111111111111111111111111111111111");
	private final ValidatorMiningViewService service =
			new ValidatorMiningViewService(new ValidatorMiningPolicyService());

	@Test
	void legacyUnlimitedIsNeverPresentedAsZeroEligibility() {
		WorldState state = state(legacy(), window(75));

		ValidatorMiningView view = service.evaluate(state, VALIDATOR, legacy());

		assertThat(view.miningPolicySource()).isEqualTo(MiningPolicySource.LEGACY_DEFAULT);
		assertThat(view.miningLimitMode()).isEqualTo(MiningLimitMode.UNLIMITED);
		assertThat(view.maxMiningShareBps()).isZero();
		assertThat(view.maxBlocksInCurrentWindow()).isNull();
		assertThat(view.blocksMinedInCurrentWindow()).isEqualTo(75);
		assertThat(view.remainingBlocksInCurrentWindow()).isNull();
		assertThat(view.miningEligible()).isTrue();
	}

	@Test
	void limitedViewUsesCanonicalHeadWindowForQuotaAndEligibility() {
		ValidatorStateImpl limited = explicitLimited();
		WorldState state = state(limited, window(40));

		ValidatorMiningView view = service.evaluate(state, VALIDATOR, limited);

		assertThat(view.miningPolicySource()).isEqualTo(MiningPolicySource.EXPLICIT);
		assertThat(view.maxBlocksInCurrentWindow()).isEqualTo(40);
		assertThat(view.blocksMinedInCurrentWindow()).isEqualTo(40);
		assertThat(view.remainingBlocksInCurrentWindow()).isZero();
		assertThat(view.miningEligible()).isFalse();
	}

	private WorldState state(ValidatorStateImpl validator, MiningWindowState window) {
		WorldState state = mock(WorldState.class);
		when(state.getValidator(VALIDATOR)).thenReturn(validator);
		when(state.getParams()).thenReturn(NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.validatorMiningWindowBlocks(100)
				.currentValidatorCount(1)
				.currentUnlimitedValidatorCount(validator.getMiningLimitMode() == MiningLimitMode.UNLIMITED ? 1 : 0)
				.build());
		when(state.getMiningWindow()).thenReturn(window);
		return state;
	}

	private MiningWindowState window(int count) {
		MiningWindowStateImpl window = MiningWindowStateImpl.empty(100, 10);
		for (int i = 0; i < count; i++) {
			window = window.append(VALIDATOR, 11 + i);
		}
		return window;
	}

	private ValidatorStateImpl legacy() {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V1)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(Instant.EPOCH)
				.build();
	}

	private ValidatorStateImpl explicitLimited() {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(Instant.EPOCH)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(4_000)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(2)
				.policyUpdatedAtTimestamp(Instant.EPOCH)
				.build();
	}
}
