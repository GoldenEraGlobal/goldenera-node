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
package global.goldenera.node.core.blockchain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

class DeterministicSandboxChainClockTest {

	private static final long GENESIS_MS = 1_800_000_000_000L;
	DeterministicSandboxChainClock clock;

	@BeforeEach
	void setUp() {
		SandboxManifestContext manifest = new SandboxManifestLoader()
				.load(Path.of("src/test/resources/sandbox/manifest/manifest-v1-valid.json").toAbsolutePath());
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifest));
		clock = new DeterministicSandboxChainClock(runtime);
	}

	@Test
	void derivesSequenceFromGenesisAndHeightAndClampsAfterParent() {
		assertThat(clock.nextBlockTimestamp(parent(4, at(4_000)))).isEqualTo(at(5_000));
		assertThat(clock.nextBlockTimestamp(parent(4, at(5_100)))).isEqualTo(at(5_101));
	}

	@Test
	void concurrentConsumersObserveTheSamePureTimestampWithoutSharedMutation() throws Exception {
		BlockHeader parent = parent(4, at(4_000));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Instant> first = executor.submit(() -> {
				start.await();
				return clock.nextBlockTimestamp(parent);
			});
			Future<Instant> second = executor.submit(() -> {
				start.await();
				return clock.nextBlockTimestamp(parent);
			});
			start.countDown();

			assertThat(List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
					.containsExactly(at(5_000), at(5_000));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void explicitReservationIsParentBoundSingleUseAndWithinConsensusWindow() {
		BlockHeader parent = parent(4, at(4_000));
		try (BlockTimestampReservation reservation = clock.reserveNextBlockTimestamp(
				parent, Optional.of(at(7_000)))) {
			assertThat(reservation.consume(parent)).isEqualTo(at(7_000));
			assertThat(reservation.isConsumed()).isTrue();
			assertThatThrownBy(() -> reservation.consume(parent))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("already");
		}
	}

	@Test
	void reservationRejectsDifferentParentAndOutOfWindowTimestamp() {
		BlockHeader parent = parent(4, at(4_000));
		try (BlockTimestampReservation reservation = clock.reserveNextBlockTimestamp(parent, Optional.empty())) {
			assertThatThrownBy(() -> reservation.consume(parent(5, at(4_000))))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("parent");
		}
		assertThatThrownBy(() -> clock.reserveNextBlockTimestamp(parent, Optional.of(at(35_001))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("future");
	}

	@Test
	void validationUsesSameDeterministicWindowAndRejectsFutureTimestamp() {
		BlockHeader parent = parent(4, at(4_000));
		clock.validateBlockTimestamp(child(5, at(5_000)), parent, Long.MAX_VALUE);
		clock.validateBlockTimestamp(child(5, at(35_000)), parent, 0);

		assertThatThrownBy(() -> clock.validateBlockTimestamp(child(5, at(35_001)), parent, Long.MAX_VALUE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Timestamp too far in future");
		assertThatThrownBy(() -> clock.validateBlockTimestamp(child(6, at(6_000)), parent, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("child height");
	}

	@Test
	void deterministicClockCannotBeActivatedForProductionRuntime() {
		SandboxRuntimeContext production = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());
		assertThatThrownBy(() -> new DeterministicSandboxChainClock(production))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SANDBOX");
	}

	private Instant at(long offsetMs) {
		return Instant.ofEpochMilli(GENESIS_MS + offsetMs);
	}

	private BlockHeader parent(long height, Instant timestamp) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getTimestamp()).thenReturn(timestamp);
		when(header.getHash()).thenReturn(Hash.fromHexString(String.format("0x%064x", height + timestamp.toEpochMilli())));
		return header;
	}

	private BlockHeader child(long height, Instant timestamp) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getTimestamp()).thenReturn(timestamp);
		return header;
	}
}
