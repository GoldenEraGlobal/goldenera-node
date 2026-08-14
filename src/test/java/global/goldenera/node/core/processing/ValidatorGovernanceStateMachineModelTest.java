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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayloadImpl;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateEncoder;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;

class ValidatorGovernanceStateMachineModelTest {

	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
	private static final Hash ACTION = Hash.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000042");
	private static final List<Address> POOL = addresses(12);

	@Test
	void fixedSeedGovernanceAndMiningSequenceMatchesIndependentStateMachine() {
		ValidatorMiningGovernanceService governance = new ValidatorMiningGovernanceService();
		ValidatorMiningPolicyService mining = new ValidatorMiningPolicyService();
		Harness harness = new Harness();
		Model model = new Model(100);
		model.validators.put(POOL.get(0), MiningLimitMode.UNLIMITED);
		model.validators.put(POOL.get(1), MiningLimitMode.LIMITED);
		model.validators.put(POOL.get(2), MiningLimitMode.LIMITED);
		harness.addInitial(POOL.get(0), MiningLimitMode.UNLIMITED);
		harness.addInitial(POOL.get(1), MiningLimitMode.LIMITED);
		harness.addInitial(POOL.get(2), MiningLimitMode.LIMITED);
		harness.params.set(params(3, 1, 100));

		Random random = new Random(0x676f7665726eL);
		int[] actionCounts = new int[5];
		for (int step = 0; step < 500; step++) {
			long height = 11L + step;
			int action = random.nextInt(actionCounts.length);
			boolean resized = false;
			switch (action) {
			case 0 -> actionCounts[action]++;
				case 1 -> {
					List<Address> absent = POOL.stream().filter(address -> !model.validators.containsKey(address)).toList();
					if (!absent.isEmpty()) {
						Address address = absent.get(random.nextInt(absent.size()));
						MiningLimitMode mode = random.nextBoolean()
								? MiningLimitMode.UNLIMITED : MiningLimitMode.LIMITED;
						governance.addValidator(harness.state, add(address, mode), block(height, address), ACTION, true);
						model.validators.put(address, mode);
						actionCounts[action]++;
					}
				}
				case 2 -> {
					List<Address> removable = model.validators.entrySet().stream()
							.filter(entry -> entry.getValue() != MiningLimitMode.UNLIMITED || model.unlimited() > 1)
							.map(Map.Entry::getKey).toList();
					if (!removable.isEmpty()) {
						Address address = removable.get(random.nextInt(removable.size()));
						governance.removeValidator(harness.state,
								TxBipValidatorRemovePayloadImpl.builder().address(address).build(),
								block(height, address), ACTION, true);
						model.validators.remove(address);
						actionCounts[action]++;
					}
				}
				case 3 -> {
					List<Address> changeable = model.validators.entrySet().stream()
							.filter(entry -> entry.getValue() != MiningLimitMode.UNLIMITED || model.unlimited() > 1)
							.map(Map.Entry::getKey).toList();
					if (!changeable.isEmpty()) {
						Address address = changeable.get(random.nextInt(changeable.size()));
						MiningLimitMode mode = model.validators.get(address) == MiningLimitMode.UNLIMITED
								? MiningLimitMode.LIMITED : MiningLimitMode.UNLIMITED;
						governance.setMiningPolicy(harness.state, policy(address, mode), block(height, address), ACTION);
						model.validators.put(address, mode);
						actionCounts[action]++;
					}
				}
				case 4 -> {
					long requested = List.of(100L, 101L, 250L, 1_000L, 10_000L)
							.get(random.nextInt(5));
					if (requested != model.windowSize) {
						governance.setNetworkParams(harness.state, resize(requested), block(height, POOL.getFirst()),
								ACTION, true);
						model.resize(requested, height);
						resized = true;
						actionCounts[action]++;
					}
				}
				default -> throw new IllegalStateException("Unexpected action: " + action);
			}

			Address miner = new ArrayList<>(model.validators.keySet())
					.get(random.nextInt(model.validators.size()));
			mining.appendAcceptedBlock(harness.state, block(height, miner), true);
			if (!resized) {
				model.append(miner, height);
			}
			assertMatches(harness, model);
		}

		assertThat(actionCounts).doesNotContain(0);
	}

	private void assertMatches(Harness harness, Model model) {
		NetworkParamsStateImpl actualParams = harness.params.get();
		assertThat(actualParams.getCurrentValidatorCount()).isEqualTo(model.validators.size());
		assertThat(actualParams.getCurrentUnlimitedValidatorCount()).isEqualTo(model.unlimited());
		assertThat(actualParams.getValidatorMiningWindowBlocks()).isEqualTo(model.windowSize);
		assertThat(harness.validators).containsOnlyKeys(model.validators.keySet());
		model.validators.forEach((address, mode) ->
				assertThat(harness.validators.get(address).getMiningLimitMode()).isEqualTo(mode));
		MiningWindowStateImpl expected = new MiningWindowStateImpl(
				harness.window.get().getVersion(), model.windowSize, new ArrayList<>(model.ordered),
				model.counts, model.lastHeight);
		assertThat(MiningWindowStateEncoder.INSTANCE.encode(harness.window.get()))
				.isEqualTo(MiningWindowStateEncoder.INSTANCE.encode(expected));
	}

	private static TxBipValidatorAddPayloadImpl add(Address address, MiningLimitMode mode) {
		return TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(address)
				.miningLimitMode(mode)
				.maxMiningShareBps(mode == MiningLimitMode.LIMITED ? 2_500L : 0L)
				.build();
	}

	private static TxBipValidatorMiningPolicySetPayloadImpl policy(Address address, MiningLimitMode mode) {
		return TxBipValidatorMiningPolicySetPayloadImpl.builder()
				.validatorAddress(address)
				.miningLimitMode(mode)
				.maxMiningShareBps(mode == MiningLimitMode.LIMITED ? 4_000 : 0)
				.build();
	}

	private static TxBipNetworkParamsSetPayloadImpl resize(long window) {
		return TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.validatorMiningWindowBlocks(window)
				.build();
	}

	private static SimpleBlock block(long height, Address identity) {
		return SimpleBlock.builder().height(height).timestamp(TIME.plusSeconds(height))
				.coinbase(Address.ZERO).identity(identity).build();
	}

	private static ValidatorStateImpl validator(MiningLimitMode mode) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(ACTION)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.miningLimitMode(mode)
				.maxMiningShareBps(mode == MiningLimitMode.LIMITED ? 2_500 : 0)
				.policyUpdatedByTxHash(ACTION)
				.policyUpdatedAtBlockHeight(1)
				.policyUpdatedAtTimestamp(TIME)
				.build();
	}

	private static NetworkParamsStateImpl params(long validators, long unlimited, long window) {
		return NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.blockReward(Wei.ZERO).blockRewardPoolAddress(Address.ZERO)
				.targetMiningTimeMs(30_000).asertHalfLifeBlocks(64).asertAnchorHeight(0)
				.minDifficulty(BigInteger.ONE).minTxBaseFee(Wei.ZERO).minTxByteFee(Wei.ZERO)
				.updatedByTxHash(ACTION).currentAuthorityCount(1)
				.currentValidatorCount(validators).currentUnlimitedValidatorCount(unlimited)
				.validatorMiningWindowBlocks(window).updatedAtBlockHeight(10).updatedAtTimestamp(TIME)
				.build();
	}

	private static List<Address> addresses(int count) {
		List<Address> addresses = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			addresses.add(Address.fromHexString(String.format("0x%040x", index)));
		}
		return List.copyOf(addresses);
	}

	private static final class Harness {
		private final WorldState state = mock(WorldState.class);
		private final Map<Address, ValidatorState> validators = new LinkedHashMap<>();
		private final AtomicReference<NetworkParamsStateImpl> params = new AtomicReference<>();
		private final AtomicReference<MiningWindowStateImpl> window =
				new AtomicReference<>(MiningWindowStateImpl.empty(100, 10));

		private Harness() {
			when(state.getParams()).thenAnswer(invocation -> params.get());
			when(state.getMiningWindow()).thenAnswer(invocation -> window.get());
			when(state.getValidator(any(Address.class))).thenAnswer(invocation ->
					validators.getOrDefault(invocation.getArgument(0), ValidatorStateImpl.ZERO));
			when(state.isParamsChangedThisBlock()).thenReturn(false);
			doAnswer(invocation -> {
				validators.put(invocation.getArgument(0), invocation.getArgument(1));
				return null;
			}).when(state).addValidator(any(Address.class), any(ValidatorState.class));
			doAnswer(invocation -> {
				validators.remove(invocation.getArgument(0));
				return null;
			}).when(state).removeValidator(any(Address.class));
			doAnswer(invocation -> {
				params.set(invocation.getArgument(0));
				return null;
			}).when(state).setParams(any());
			doAnswer(invocation -> {
				window.set(invocation.getArgument(0));
				return null;
			}).when(state).setMiningWindow(any());
		}

		private void addInitial(Address address, MiningLimitMode mode) {
			validators.put(address, validator(mode));
		}
	}

	private static final class Model {
		private final Map<Address, MiningLimitMode> validators = new LinkedHashMap<>();
		private final Deque<Address> ordered = new ArrayDeque<>();
		private final Map<Address, Long> counts = new LinkedHashMap<>();
		private long windowSize;
		private long lastHeight = 10;

		private Model(long windowSize) {
			this.windowSize = windowSize;
		}

		private long unlimited() {
			return validators.values().stream().filter(mode -> mode == MiningLimitMode.UNLIMITED).count();
		}

		private void append(Address identity, long height) {
			if (ordered.size() == windowSize) {
				Address evicted = ordered.removeFirst();
				long remaining = counts.get(evicted) - 1;
				if (remaining == 0) {
					counts.remove(evicted);
				} else {
					counts.put(evicted, remaining);
				}
			}
			ordered.addLast(identity);
			counts.merge(identity, 1L, Long::sum);
			lastHeight = height;
		}

		private void resize(long requested, long height) {
			windowSize = requested;
			ordered.clear();
			counts.clear();
			lastHeight = height;
		}
	}
}
