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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateDecoder;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateEncoder;

class MiningWindowModelHardeningTest {

	private static final long SEED = 0x6d696e696e674cL;
	private static final List<Address> IDENTITIES = List.of(
			address(1), address(2), address(3), address(4), address(5), address(6), address(7));

	@Test
	void fixedSeedRollingWindowMatchesIndependentDequeAndMapModelAtAllSupportedScales() {
		for (int windowSize : List.of(100, 101, 250, 1_000, 10_000)) {
			int operations = windowSize == 10_000 ? 10_257 : windowSize * 7;
			Random random = new Random(SEED ^ windowSize);
			ReferenceWindow reference = new ReferenceWindow(windowSize);
			MiningWindowStateImpl actual = MiningWindowStateImpl.empty(windowSize, 10);

			for (int operation = 0; operation < operations; operation++) {
				Address identity = IDENTITIES.get(random.nextInt(IDENTITIES.size()));
				long height = 11L + operation;
				reference.append(identity, height);
				actual = actual.append(identity, height);

				if (operation % 31 == 0 || operation == operations - 1) {
					assertMatches(reference, actual);
					Bytes encoded = MiningWindowStateEncoder.INSTANCE.encode(actual);
					assertThat(MiningWindowStateDecoder.INSTANCE.decode(encoded)).isEqualTo(actual);
				}
			}
		}
	}

	@Test
	void forkReplayAndResizeResetAreDeterministicAndBranchLocal() {
		ReferenceWindow baseModel = new ReferenceWindow(250);
		MiningWindowStateImpl base = MiningWindowStateImpl.empty(250, 10);
		Random random = new Random(SEED);
		for (int index = 0; index < 400; index++) {
			Address identity = IDENTITIES.get(random.nextInt(IDENTITIES.size()));
			baseModel.append(identity, 11L + index);
			base = base.append(identity, 11L + index);
		}
		assertMatches(baseModel, base);

		MiningWindowStateImpl branchA = base;
		MiningWindowStateImpl branchB = base;
		for (int index = 0; index < 75; index++) {
			branchA = branchA.append(IDENTITIES.get(index % 3), 411L + index);
			branchB = branchB.append(IDENTITIES.get(4 + index % 3), 411L + index);
		}
		assertThat(MiningWindowStateEncoder.INSTANCE.encode(branchA))
				.isNotEqualTo(MiningWindowStateEncoder.INSTANCE.encode(branchB));

		MiningWindowStateImpl replay = base;
		for (int index = 0; index < 75; index++) {
			replay = replay.append(IDENTITIES.get(index % 3), 411L + index);
		}
		assertThat(MiningWindowStateEncoder.INSTANCE.encode(replay))
				.isEqualTo(MiningWindowStateEncoder.INSTANCE.encode(branchA));

		MiningWindowStateImpl resized = MiningWindowStateImpl.empty(1_000, 486);
		assertThat(resized.getOrderedValidatorIdentities()).isEmpty();
		assertThat(resized.getValidatorBlockCounts()).isEmpty();
		assertThat(resized.getLastUpdatedBlockHeight()).isEqualTo(486);
		assertThat(resized.append(IDENTITIES.getFirst(), 487).getOrderedValidatorIdentities())
				.containsExactly(IDENTITIES.getFirst());
	}

	@Test
	void quotaBoundariesMatchIntegerFloorModelForEveryRequestedWindowAndShare() {
		ValidatorMiningPolicyService service = new ValidatorMiningPolicyService();
		for (long window : List.of(100L, 101L, 250L, 1_000L, 10_000L)) {
			for (long share : List.of(1L, 100L, 2_500L, 3_333L, 4_000L)) {
				long expected = window * share / 10_000L;
				assertThat(service.calculateMaxBlocks(window, share))
						.as("window=%s share=%s", window, share)
						.isEqualTo(expected);
			}
		}
	}

	private void assertMatches(ReferenceWindow expected, MiningWindowStateImpl actual) {
		assertThat(actual.getWindowSize()).isEqualTo(expected.windowSize);
		assertThat(actual.getOrderedValidatorIdentities()).containsExactlyElementsOf(expected.ordered);
		assertThat(actual.getValidatorBlockCounts()).containsExactlyInAnyOrderEntriesOf(expected.counts);
		assertThat(actual.getLastUpdatedBlockHeight()).isEqualTo(expected.lastHeight);
		assertThat(actual.getValidatorBlockCounts().values()).allMatch(count -> count > 0);
	}

	private static Address address(long value) {
		return Address.fromHexString(String.format("0x%040x", value));
	}

	private static final class ReferenceWindow {
		private final int windowSize;
		private final Deque<Address> ordered = new ArrayDeque<>();
		private final Map<Address, Long> counts = new LinkedHashMap<>();
		private long lastHeight = 10;

		private ReferenceWindow(int windowSize) {
			this.windowSize = windowSize;
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
	}
}
