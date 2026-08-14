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
package global.goldenera.node.core.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.pow.DeterministicSha256ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.time.DeterministicSandboxChainClock;
import global.goldenera.node.core.mining.AutonomousMiningState;
import global.goldenera.node.core.mining.ExactOneMiningOutcome;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.mining.MiningSuspensionReason;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AutonomousRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.ExactOneRequest;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.BlockIngestionOutcome;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SandboxControlServiceTest {

	@TempDir
	Path temporaryDirectory;

	MiningService miningService;
	SandboxControlOperationRegistry operations;
	SandboxControlAuditLog auditLog;
	SandboxControlService service;

	@AfterEach
	void tearDown() {
		operations.close();
	}

	@BeforeEach
	void setUp() throws Exception {
		miningService = mock(MiningService.class);
		auditLog = new SandboxControlAuditLog();
		operations = new SandboxControlOperationRegistry(auditLog);
		SandboxManifestContext manifest = controlManifest();
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifest));
		SandboxControlProperties properties = new SandboxControlProperties();
		properties.setEnabled(true);
		properties.setTokenFile(temporaryDirectory.resolve("token"));
		SandboxControlActivation activation = new SandboxControlActivationValidator().validate(runtime, properties);
		AuthoritativeChainIdentityProvider identityProvider = mock(AuthoritativeChainIdentityProvider.class);
		when(identityProvider.identity()).thenReturn(new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				Network.TESTNET.getCode(),
				manifest.manifest().chainId(),
				manifest.manifest().genesis().expectedGenesisHash(),
				manifest.fingerprint()));
		service = new SandboxControlService(
				miningService,
				activation,
				operations,
				auditLog,
				DeterministicSha256ProofOfWorkProvider.from(manifest),
				new DeterministicSandboxChainClock(runtime),
				new MiningProperties(),
				identityProvider,
				() -> true);
	}

	@Test
	void exactOneIsAsyncIdempotentAndPermitIsHeldUntilTerminalCompletion() {
		CompletableFuture<ExactOneMiningOutcome> pending = new CompletableFuture<>();
		when(miningService.mineExactlyOne(any())).thenReturn(pending);
		ExactOneRequest body = new ExactOneRequest(Instant.parse("2027-01-01T00:00:00Z"), 30_000L);

		SandboxControlService.Submission first = service.submitExactOne("same-key", body);
		SandboxControlService.Submission replay = service.submitExactOne("same-key", body);

		assertThat(first.replayed()).isFalse();
		assertThat(first.operation().status()).isEqualTo("PENDING");
		assertThat(replay.replayed()).isTrue();
		assertThat(replay.operation().operationId()).isEqualTo(first.operation().operationId());
		SandboxControlAuditLog.Event admitted = auditLog.page(0, 100).events().stream()
				.filter(event -> "ADMITTED".equals(event.result()))
				.findFirst()
				.orElseThrow();
		assertThat(admitted.operationId()).isEqualTo(first.operation().operationId());
		assertThat(admitted.requestId()).isNotBlank().isNotEqualTo("same-key");
		assertThatThrownBy(() -> service.submitExactOne("same-key", new ExactOneRequest(null, 30_000L)))
				.isInstanceOf(SandboxControlException.class)
				.extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThatThrownBy(() -> service.submitExactOne("second-key", body))
				.isInstanceOf(SandboxControlException.class)
				.extracting("code").isEqualTo("MUTATION_BUSY");
		assertThatThrownBy(() -> service.setAutonomous(new AutonomousRequest(false)))
				.isInstanceOf(SandboxControlException.class)
				.extracting("code").isEqualTo("MUTATION_BUSY");

		pending.complete(new ExactOneMiningOutcome(
				ExactOneMiningOutcome.Code.ACCEPTED,
				null,
				12L,
				null,
				BlockIngestionOutcome.Code.ACCEPTED));

		assertThat(service.operation(first.operation().operationId()).outcome().code()).isEqualTo("ACCEPTED");
		assertThat(service.operation(first.operation().operationId()).outcome().ingestionCode()).isEqualTo("ACCEPTED");
		when(miningService.mineExactlyOne(any())).thenReturn(new CompletableFuture<>());
		assertThat(service.submitExactOne("second-key", body).operation().operationId())
				.isNotEqualTo(first.operation().operationId());
	}

	@Test
	void independentDeadlineTerminalizesPendingOperationAndReleasesMutation() throws Exception {
		when(miningService.mineExactlyOne(any())).thenReturn(new CompletableFuture<>());
		ExactOneRequest body = new ExactOneRequest(null, 1L);
		String operationId = service.submitExactOne("deadline-key", body).operation().operationId();

		long timeoutNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while ("PENDING".equals(service.operation(operationId).status()) && System.nanoTime() < timeoutNanos) {
			TimeUnit.MILLISECONDS.sleep(10);
		}

		assertThat(service.operation(operationId).outcome().code()).isEqualTo("TIMED_OUT");
		assertThat(auditLog.page(0, 100).events()).anyMatch(event ->
				"TIMED_OUT".equals(event.result()) && operationId.equals(event.operationId()));
		assertThat(service.submitExactOne("after-timeout", new ExactOneRequest(null, 30_000L))
				.operation().operationId()).isNotEqualTo(operationId);
	}

	@Test
	void publicOutcomeMappingsAreExhaustiveForEveryCurrentEnumValue() {
		for (ExactOneMiningOutcome.Code code : ExactOneMiningOutcome.Code.values()) {
			assertThat(SandboxControlOperationRegistry.map(ExactOneMiningOutcome.of(code)).code())
					.isEqualTo(code.name());
		}
		for (BlockIngestionOutcome.Code code : BlockIngestionOutcome.Code.values()) {
			ExactOneMiningOutcome outcome = new ExactOneMiningOutcome(
					ExactOneMiningOutcome.Code.REJECTED_BY_INGESTION,
					null,
					null,
					null,
					code);
			assertThat(SandboxControlOperationRegistry.map(outcome).ingestionCode()).isEqualTo(code.name());
		}
	}

	@Test
	void structuredAuditLoggerNeverEmitsRawIdempotencySentinel() {
		Logger logger = (Logger) LoggerFactory.getLogger(SandboxControlAuditLog.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			when(miningService.mineExactlyOne(any())).thenReturn(new CompletableFuture<>());
			String sentinel = "secret-idempotency-sentinel";

			service.submitExactOne(sentinel, new ExactOneRequest(null, 30_000L));

			assertThat(appender.list)
					.extracting(ILoggingEvent::getFormattedMessage)
					.noneMatch(message -> message.contains(sentinel));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void autonomousControlResumeNeverClearsSyncAndPauseUsesBoundedQuiescence() {
		AutonomousMiningState syncingAndControlPaused = new AutonomousMiningState(
				true, true, false, false, false,
				EnumSet.of(MiningSuspensionReason.SYNC, MiningSuspensionReason.SANDBOX_CONTROL));
		when(miningService.getAutonomousMiningState()).thenReturn(syncingAndControlPaused);

		assertThat(service.setAutonomous(new AutonomousRequest(true)).syncing()).isTrue();
		verify(miningService).resumeAutonomousMining();
		verify(miningService).startMining();

		when(miningService.pauseAutonomousMining(SandboxControlService.AUTONOMOUS_PAUSE_TIMEOUT)).thenReturn(true);
		assertThat(service.setAutonomous(new AutonomousRequest(false)).controlPaused()).isTrue();
		verify(miningService).pauseAutonomousMining(SandboxControlService.AUTONOMOUS_PAUSE_TIMEOUT);
	}

	@Test
	void auditIsBoundedRedactedAndPagesAtMostOneHundred() {
		for (int index = 0; index < 300; index++) {
			auditLog.record(SandboxControlAuditLog.Action.AUTHENTICATE, "REJECTED", null);
		}

		SandboxControlAuditLog.Page first = auditLog.page(0, 1000);
		assertThat(first.events()).hasSize(SandboxControlAuditLog.MAX_PAGE_SIZE);
		assertThat(first.hasMore()).isTrue();
		SandboxControlAuditLog.Page second = auditLog.page(first.nextAfter(), 100);
		assertThat(second.events()).isNotEmpty();
		assertThat(second.events().getFirst().sequence()).isGreaterThan(first.nextAfter());
		assertThat(first.toString())
				.doesNotContain("Bearer", "Idempotency-Key", "token");
	}

	private SandboxManifestContext controlManifest() throws Exception {
		String json;
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		Path path = temporaryDirectory.resolve("control-manifest.json");
		Files.writeString(path, json.replace("\"controlApi\": false", "\"controlApi\": true"),
				StandardCharsets.UTF_8);
		return new SandboxManifestLoader().load(path);
	}
}
