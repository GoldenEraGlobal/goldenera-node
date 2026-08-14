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
package global.goldenera.node.core.mining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkHasher;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkMiningException;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.BlockIngestionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MiningServiceParentLifecycleTest {

	@Test
	void canonicalParentChangeInterruptsCurrentNonceSearch() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread nonceSearch = new Thread(() -> {
			started.countDown();
			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(10));
			} catch (InterruptedException e) {
				interrupted.set(true);
				Thread.currentThread().interrupt();
			}
		});
		nonceSearch.start();
		assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
		miningFlag(fixture.service).set(true);
		ReflectionTestUtils.setField(fixture.service, "miningThread", nonceSearch);
		when(fixture.proofOfWorkProvider.isInitializationInProgress()).thenReturn(false);

		fixture.service.onNewBlockConnected(mock(BlockConnectedEvent.class));

		nonceSearch.join(TimeUnit.SECONDS.toMillis(2));
		assertThat(interrupted).isTrue();
		assertThat(nonceSearch.isAlive()).isFalse();
		miningFlag(fixture.service).set(false);
	}

	@Test
	void staleTemplateFromPreviousParentIsNeverPublished() {
		Fixture fixture = fixture();
		Hash oldParent = hash(1);
		Hash currentParent = hash(2);
		StoredBlock currentTip = mock(StoredBlock.class);
		when(currentTip.getHash()).thenReturn(currentParent);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);
		MiningBlockAssemblerService.BlockHeaderTemplate template = MiningBlockAssemblerService.BlockHeaderTemplate
				.builder()
				.version(BlockVersion.V1)
				.height(12)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(oldParent)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.fromHexString("0x0000000000000000000000000000000000000001"))
				.build();
		MiningBlockAssemblerService.AssembledBlock assembled = MiningBlockAssemblerService.AssembledBlock.builder()
				.blockTemplate(template)
				.txs(List.of())
				.invalidTxs(List.of())
				.build();

		ReflectionTestUtils.invokeMethod(fixture.service, "processMinedBlock", template, assembled, 7L, 1.0d);

		verify(fixture.ingestionService, never()).processBlock(any(), any(), any(), any());
	}

	@Test
	void nonceSearchUsesProviderWithCanonicalHeaderInputAndTargetComparison() {
		Fixture fixture = fixture();
		ProofOfWorkHasher hasher = mock(ProofOfWorkHasher.class);
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenReturn(hasher);
		when(hasher.hash(any(byte[].class))).thenReturn(new byte[32]);
		MiningBlockAssemblerService.BlockHeaderTemplate template = MiningBlockAssemblerService.BlockHeaderTemplate
				.builder()
				.version(BlockVersion.V1)
				.height(12L)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.fromHexString("0x0000000000000000000000000000000000000001"))
				.build();

		try {
			Long nonce = ReflectionTestUtils.invokeMethod(fixture.service, "findNonce", template,
					DifficultyUtil.calculateTargetFromDifficulty(BigInteger.ONE));

			assertThat(nonce).isZero();
			ArgumentCaptor<byte[]> input = ArgumentCaptor.forClass(byte[].class);
			verify(hasher).hash(input.capture());
			assertThat(input.getValue()).containsExactly(BlockHeaderUtil.powInput(template.toBlockHeader()));
			verify(hasher).close();
		} finally {
			ExecutorService worker = (ExecutorService) ReflectionTestUtils.getField(fixture.service,
					"blockHashingWorker");
			if (worker != null) {
				worker.shutdownNow();
			}
		}
	}

	@Test
	void nonceSearchUpdatesCanonicalNonceUntilProofMeetsTarget() {
		Fixture fixture = fixture();
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> {
			long nonce = ByteBuffer.wrap(input, input.length - Long.BYTES, Long.BYTES).getLong();
			byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
			if (nonce == 0L) {
				hash[hash.length - 1] = 1;
			}
			return hash;
		}, () -> { });
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenReturn(hasher);

		assertThat(runNonceSearch(fixture, template(), BigInteger.ZERO)).isEqualTo(1L);
	}

	@Test
	void nonceSearchPropagatesProviderOpenFailure() {
		Fixture fixture = fixture();
		when(fixture.proofOfWorkProvider.openMiningHasher())
				.thenThrow(new IllegalStateException("open failed"));

		assertThatThrownBy(() -> runNonceSearch(fixture, template(), BigInteger.ZERO))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasRootCauseMessage("open failed");
	}

	@Test
	void nonceSearchPropagatesHashFailure() {
		Fixture fixture = fixture();
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> {
			throw new IllegalStateException("hash failed");
		}, () -> { });
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenReturn(hasher);

		assertThatThrownBy(() -> runNonceSearch(fixture, template(), BigInteger.ZERO))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasRootCauseMessage("hash failed");
	}

	@Test
	void nonceSearchPropagatesCloseFailure() {
		Fixture fixture = fixture();
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES],
				() -> {
					throw new IllegalStateException("close failed");
				});
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenReturn(hasher);

		assertThatThrownBy(() -> runNonceSearch(fixture, template(), BigInteger.ZERO))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasRootCauseMessage("close failed");
	}

	@Test
	void nonceSearchRejectsMalformedProviderHash() {
		Fixture fixture = fixture();
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> new byte[31], () -> { });
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenReturn(hasher);

		assertThatThrownBy(() -> runNonceSearch(fixture, template(), BigInteger.ZERO))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasRootCauseMessage("Proof-of-work provider returned 31 bytes; expected exactly 32");
	}

	@Test
	void firstWorkerFailureStopsSiblingNonceSearchPromptly() {
		Fixture fixture = fixture(2);
		CountDownLatch hashingStarted = new CountDownLatch(1);
		AtomicBoolean firstHasher = new AtomicBoolean(true);
		when(fixture.proofOfWorkProvider.openMiningHasher()).thenAnswer(invocation -> {
			if (firstHasher.compareAndSet(true, false)) {
				return new ProofOfWorkHasher(input -> {
					hashingStarted.countDown();
					byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
					hash[hash.length - 1] = 1;
					return hash;
				}, () -> { });
			}
			hashingStarted.await(1, TimeUnit.SECONDS);
			throw new IllegalStateException("sibling open failed");
		});

		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> assertThatThrownBy(() -> runNonceSearch(fixture, template(), BigInteger.ZERO))
						.isInstanceOf(ProofOfWorkMiningException.class)
						.hasRootCauseMessage("sibling open failed"));
	}

	@Test
	void preparationFailureStopsAutonomousMiningWithoutRetryAndClosesWorkers() throws Exception {
		Fixture fixture = fixture();
		StoredBlock parent = mock(StoredBlock.class);
		Block parentBlock = mock(Block.class);
		when(parent.getBlock()).thenReturn(parentBlock);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(parent);
		MiningBlockAssemblerService.AssembledBlock assembled = MiningBlockAssemblerService.AssembledBlock.builder()
				.blockTemplate(template())
				.txs(List.of())
				.invalidTxs(List.of())
				.build();
		when(fixture.assembler.createBlockTemplate(parentBlock)).thenReturn(Optional.of(assembled));
		doThrow(new IllegalStateException("initialization failed"))
				.when(fixture.proofOfWorkProvider).prepareForMining(12L);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		ReflectionTestUtils.setField(fixture.service, "blockHashingWorker", worker);
		miningFlag(fixture.service).set(true);

		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> ReflectionTestUtils.invokeMethod(fixture.service, "runMiningLoop"));

		assertThat(miningFlag(fixture.service)).isFalse();
		assertThat(worker.isShutdown()).isTrue();
		assertThat(ReflectionTestUtils.getField(fixture.service, "miningThread")).isNull();
		verify(fixture.proofOfWorkProvider).prepareForMining(12L);
		verify(fixture.proofOfWorkProvider, never()).openMiningHasher();
	}

	private Long runNonceSearch(Fixture fixture, MiningBlockAssemblerService.BlockHeaderTemplate template,
			BigInteger target) {
		try {
			return ReflectionTestUtils.invokeMethod(fixture.service, "findNonce", template, target);
		} finally {
			ExecutorService worker = (ExecutorService) ReflectionTestUtils.getField(fixture.service,
					"blockHashingWorker");
			if (worker != null) {
				worker.shutdownNow();
			}
		}
	}

	private MiningBlockAssemblerService.BlockHeaderTemplate template() {
		return MiningBlockAssemblerService.BlockHeaderTemplate.builder()
				.version(BlockVersion.V1)
				.height(12L)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.fromHexString("0x0000000000000000000000000000000000000001"))
				.build();
	}

	private Fixture fixture() {
		return fixture(1);
	}

	private Fixture fixture(int hashingThreads) {
		MiningProperties properties = new MiningProperties();
		properties.setEnable(true);
		properties.setHashingThreads(hashingThreads);
		ChainQuery chainQuery = mock(ChainQuery.class);
		ProofOfWorkProvider proofOfWorkProvider = mock(ProofOfWorkProvider.class);
		MiningBlockAssemblerService assembler = mock(MiningBlockAssemblerService.class);
		BlockIngestionService ingestionService = mock(BlockIngestionService.class);
		MiningService service = new MiningService(
				new SimpleMeterRegistry(), new ReentrantLock(), mock(ExecutorService.class),
				assembler, mock(IdentityService.class), mock(MempoolManager.class),
				chainQuery, properties, proofOfWorkProvider, mock(ChainClock.class), ingestionService,
				Executors.defaultThreadFactory());
		return new Fixture(service, chainQuery, proofOfWorkProvider, assembler, ingestionService);
	}

	private AtomicBoolean miningFlag(MiningService service) {
		return (AtomicBoolean) ReflectionTestUtils.getField(service, "isMining");
	}

	private Hash hash(int suffix) {
		return Hash.fromHexString(String.format("0x%064x", suffix));
	}

	private record Fixture(MiningService service, ChainQuery chainQuery,
			ProofOfWorkProvider proofOfWorkProvider, MiningBlockAssemblerService assembler,
			BlockIngestionService ingestionService) {
	}
}
