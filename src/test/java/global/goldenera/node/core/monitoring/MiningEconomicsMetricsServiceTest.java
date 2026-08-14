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
package global.goldenera.node.core.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MiningEconomicsMetricsServiceTest {

	private static final Address UNLIMITED = address(1);
	private static final Address LIMITED = address(2);

	@Test
	void publishesAggregateMetricsWithoutValidatorAddressLabels() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		WorldState state = mock(WorldState.class);
		NetworkParamsState params = mock(NetworkParamsState.class);
		when(params.getVersion()).thenReturn(NetworkParamsStateVersion.V2);
		when(params.getCurrentValidatorCount()).thenReturn(2L);
		when(params.getCurrentUnlimitedValidatorCount()).thenReturn(1L);
		when(state.getParams()).thenReturn(params);
		MiningWindowStateImpl window = MiningWindowStateImpl.empty(100, 10)
				.append(UNLIMITED, 11)
				.append(UNLIMITED, 12)
				.append(LIMITED, 13)
				.append(UNLIMITED, 14)
				.append(UNLIMITED, 15)
				.append(UNLIMITED, 16);
		when(state.getMiningWindow()).thenReturn(window);
		ValidatorState unlimitedValidator = validator(MiningLimitMode.UNLIMITED);
		ValidatorState limitedValidator = validator(MiningLimitMode.LIMITED);
		when(state.getValidator(UNLIMITED)).thenReturn(unlimitedValidator);
		when(state.getValidator(LIMITED)).thenReturn(limitedValidator);
		MiningEconomicsMetricsService service = new MiningEconomicsMetricsService(
				registry, mock(ChainHeadStateCache.class), new ValidatorMiningPolicyService());

		service.refresh(state);

		assertThat(registry.get("blockchain.mining.active_validators").tag("mode", "unlimited")
				.gauge().value()).isEqualTo(1);
		assertThat(registry.get("blockchain.mining.active_validators").tag("mode", "limited")
				.gauge().value()).isEqualTo(1);
		assertThat(registry.get("blockchain.mining.unlimited_block.share").gauge().value())
				.isEqualTo(5.0 / 6.0);
		assertThat(registry.get("blockchain.mining.same_identity.current_run").gauge().value()).isEqualTo(3);
		assertThat(registry.get("blockchain.mining.same_identity.longest_run").gauge().value()).isEqualTo(3);
		assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
				.noneMatch(tag -> tag.getValue().startsWith("0x")));
	}

	@Test
	void initializesMetricsOnlyAfterCoreDatabaseAndHeadAreReady() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		WorldState state = mock(WorldState.class);
		NetworkParamsState params = mock(NetworkParamsState.class);
		when(params.getVersion()).thenReturn(NetworkParamsStateVersion.V1);
		when(params.getCurrentValidatorCount()).thenReturn(3L);
		when(params.getCurrentUnlimitedValidatorCount()).thenReturn(3L);
		when(state.getParams()).thenReturn(params);
		ChainHeadStateCache stateCache = mock(ChainHeadStateCache.class);
		when(stateCache.getHeadState()).thenReturn(state);
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock head = mock(StoredBlock.class, Answers.RETURNS_DEEP_STUBS);
		when(head.getBlock().getHeader().getTimestamp()).thenReturn(Instant.now().minusSeconds(5));
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(head);
		MiningEconomicsMetricsService service = new MiningEconomicsMetricsService(
				registry, stateCache, chainQuery, new ValidatorMiningPolicyService());

		service.onCoreDbReady(new CoreDbReadyEvent(this));

		assertThat(registry.get("blockchain.mining.active_validators").tag("mode", "unlimited")
				.gauge().value()).isEqualTo(3);
		assertThat(registry.get("blockchain.mining.seconds_since_last_block").gauge().value())
				.isGreaterThanOrEqualTo(5);
	}

	private ValidatorState validator(MiningLimitMode mode) {
		ValidatorState validator = mock(ValidatorState.class);
		when(validator.exists()).thenReturn(true);
		when(validator.getVersion()).thenReturn(ValidatorStateVersion.V2);
		when(validator.getMiningLimitMode()).thenReturn(mode);
		when(validator.getMaxMiningShareBps()).thenReturn(mode == MiningLimitMode.UNLIMITED ? 0L : 1_000L);
		when(validator.getPolicyUpdatedAtTimestamp()).thenReturn(Instant.EPOCH);
		return validator;
	}

	private static Address address(long suffix) {
		return Address.fromHexString(String.format("0x%040x", suffix));
	}
}
