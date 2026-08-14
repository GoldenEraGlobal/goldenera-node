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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;

/** Low-cardinality operational metrics derived from the canonical head state. */
@Service
@Slf4j
public class MiningEconomicsMetricsService {

	private final ChainHeadStateCache chainHeadStateCache;
	private final ValidatorMiningPolicyService policyService;
	private final AtomicLong activeUnlimited = new AtomicLong();
	private final AtomicLong activeLimited = new AtomicLong();
	private final AtomicLong unlimitedWindowBlocks = new AtomicLong();
	private final AtomicLong totalWindowBlocks = new AtomicLong();
	private final AtomicLong currentIdentityRun = new AtomicLong();
	private final AtomicLong longestIdentityRun = new AtomicLong();
	private final AtomicReference<Instant> lastBlockTimestamp = new AtomicReference<>();

	public MiningEconomicsMetricsService(MeterRegistry registry, ChainHeadStateCache chainHeadStateCache,
			ValidatorMiningPolicyService policyService) {
		this.chainHeadStateCache = chainHeadStateCache;
		this.policyService = policyService;
		registry.gauge("blockchain.mining.active_validators", Tags.of("mode", "unlimited"), activeUnlimited);
		registry.gauge("blockchain.mining.active_validators", Tags.of("mode", "limited"), activeLimited);
		Gauge.builder("blockchain.mining.unlimited_block.share", this, MiningEconomicsMetricsService::unlimitedShare)
				.register(registry);
		registry.gauge("blockchain.mining.same_identity.current_run", currentIdentityRun);
		registry.gauge("blockchain.mining.same_identity.longest_run", longestIdentityRun);
		Gauge.builder("blockchain.mining.seconds_since_last_block", this,
				MiningEconomicsMetricsService::secondsSinceLastBlock).register(registry);
	}

	@EventListener
	@Order(Ordered.LOWEST_PRECEDENCE)
	public void onBlockConnected(BlockConnectedEvent event) {
		lastBlockTimestamp.set(event.getBlock().getHeader().getTimestamp());
		try {
			refresh(chainHeadStateCache.getHeadState());
		} catch (Exception e) {
			log.warn("Unable to refresh mining-economics metrics at height {}: {}",
					event.getBlock().getHeight(), e.getMessage());
		}
	}

	void refresh(WorldState state) {
		NetworkParamsState params = state.getParams();
		activeUnlimited.set(params.getCurrentUnlimitedValidatorCount());
		activeLimited.set(Math.max(0, params.getCurrentValidatorCount() - params.getCurrentUnlimitedValidatorCount()));
		if (params.getVersion() != NetworkParamsStateVersion.V2) {
			resetWindowMetrics();
			return;
		}

		MiningWindowState window = state.getMiningWindow();
		List<Address> identities = window.getOrderedValidatorIdentities();
		totalWindowBlocks.set(identities.size());
		long unlimitedBlocks = identities.stream().filter(identity -> isCurrentlyUnlimited(state, identity)).count();
		unlimitedWindowBlocks.set(unlimitedBlocks);
		currentIdentityRun.set(currentRun(identities));
		longestIdentityRun.set(longestRun(identities));
	}

	private boolean isCurrentlyUnlimited(WorldState state, Address identity) {
		ValidatorState validator = state.getValidator(identity);
		return validator.exists()
				&& policyService.resolveEffectivePolicy(validator).mode() == MiningLimitMode.UNLIMITED;
	}

	private long currentRun(List<Address> identities) {
		if (identities.isEmpty()) {
			return 0;
		}
		Address last = identities.get(identities.size() - 1);
		long run = 0;
		for (int i = identities.size() - 1; i >= 0 && identities.get(i).equals(last); i--) {
			run++;
		}
		return run;
	}

	private long longestRun(List<Address> identities) {
		long longest = 0;
		long current = 0;
		Address previous = null;
		for (Address identity : identities) {
			if (identity.equals(previous)) {
				current++;
			} else {
				previous = identity;
				current = 1;
			}
			longest = Math.max(longest, current);
		}
		return longest;
	}

	private double unlimitedShare() {
		long total = totalWindowBlocks.get();
		return total == 0 ? 0 : (double) unlimitedWindowBlocks.get() / total;
	}

	private double secondsSinceLastBlock() {
		Instant timestamp = lastBlockTimestamp.get();
		return timestamp == null ? 0 : Math.max(0, Duration.between(timestamp, Instant.now()).toSeconds());
	}

	private void resetWindowMetrics() {
		totalWindowBlocks.set(0);
		unlimitedWindowBlocks.set(0);
		currentIdentityRun.set(0);
		longestIdentityRun.set(0);
	}
}
