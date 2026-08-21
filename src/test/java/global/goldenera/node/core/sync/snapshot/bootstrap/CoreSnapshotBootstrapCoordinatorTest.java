/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.sync.CoreSnapshotArchiveReplayer;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.HttpCheckpointSnapshotClient;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import global.goldenera.node.core.sync.snapshot.transport.StagedSnapshotDownload;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class CoreSnapshotBootstrapCoordinatorTest {

	@Test
	void disabledBootstrapDoesNotInspectChainOrPerformHttp() {
		Fixture fixture = new Fixture(false, true);

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.DISABLED);
		verify(fixture.chainQuery, never()).getLatestBlockHeight();
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void existingChainNeverPerformsHttpBootstrap() {
		Fixture fixture = new Fixture(true, true);
		when(fixture.chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(12L));

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void networkWithoutHardcodedCheckpointNeverPerformsHttpBootstrap() {
		Fixture fixture = new Fixture(true, true);
		when(fixture.checkpointRegistry.hasConfiguredCheckpoints()).thenReturn(false);

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void nonProductionGenesisIdentityNeverPerformsTrustedHttpBootstrap() {
		Fixture fixture = new Fixture(true, true);
		when(fixture.chainIdentityProvider.identity()).thenReturn(new StoredChainIdentity(
				1, 1, "sandbox", "0x" + "ab".repeat(32), "cd".repeat(32)));

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void unavailableAtomicPipelineFallsBackBeforeDownloadingStateOnlySnapshot() {
		Fixture fixture = new Fixture(true, false);

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_PIPELINE_UNAVAILABLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void invalidFullArchiveFallsBackWithoutImportOrActivation() throws Exception {
		Fixture fixture = new Fixture(true, true);
		StagedCoreSnapshotArchiveDownload staged = stagedDownload();
		when(fixture.httpClient.stageFullArchiveFromFirstTrustedSource()).thenReturn(staged);
		when(fixture.verifier.verify(any(), any(), any(), any()))
				.thenThrow(new SnapshotVerificationException("invalid archive"));

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_INVALID_OR_UNAVAILABLE);
		verify(fixture.importer, never()).prepare(any(), any());
		verify(fixture.activator, never()).activateCanonical(any());
	}

	@Test
	void activationFailureIsFatalAndNeverReportedAsV1Fallback() throws Exception {
		Fixture fixture = new Fixture(true, true);
		StagedCoreSnapshotArchiveDownload staged = stagedDownload();
		VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		PreparedCoreSnapshotImport prepared = mock(PreparedCoreSnapshotImport.class);
		when(fixture.httpClient.stageFullArchiveFromFirstTrustedSource()).thenReturn(staged);
		when(fixture.verifier.verify(any(), any(), any(), any())).thenReturn(verified);
		when(verified.activationEligible()).thenReturn(true);
		when(fixture.importer.prepare(staged, verified)).thenReturn(prepared);
		when(prepared.verifiedArchive()).thenReturn(verified);
		org.mockito.Mockito.doThrow(new IllegalStateException("ambiguous live write"))
				.when(fixture.activator).activateCanonical(prepared);

		assertThatThrownBy(fixture.coordinator::tryBootstrapFreshNode)
				.isInstanceOf(CoreSnapshotBootstrapActivationException.class)
				.hasMessageContaining("refusing unsafe v1 fallback");
	}

	@Test
	void verifiedArchiveUsesSafeCanonicalReplayWhenAtomicSwapIsUnavailable() throws Exception {
		Fixture fixture = new Fixture(true, true, false);
		StagedCoreSnapshotArchiveDownload staged = stagedDownload();
		VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		when(fixture.httpClient.stageFullArchiveFromFirstTrustedSource()).thenReturn(staged);
		when(fixture.verifier.verify(any(), any(), any(), any())).thenReturn(verified);
		when(verified.activationEligible()).thenReturn(true);
		when(fixture.replayer.replay(staged, verified)).thenReturn(
				new CoreSnapshotArchiveReplayer.ReplayResult(10, 20, 10, mock(global.goldenera.cryptoj.datatypes.Hash.class)));

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.REPLAYED);
		verify(fixture.replayer).replay(staged, verified);
		verify(fixture.importer, never()).prepare(any(), any());
		verify(fixture.activator, never()).activateCanonical(any());
	}

	@Test
	void replayFailureFallsBackToV1BecauseOnlyAtomicCanonicalPrefixesCanHaveCommitted() throws Exception {
		Fixture fixture = new Fixture(true, true, false);
		StagedCoreSnapshotArchiveDownload staged = stagedDownload();
		VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		when(fixture.httpClient.stageFullArchiveFromFirstTrustedSource()).thenReturn(staged);
		when(fixture.verifier.verify(any(), any(), any(), any())).thenReturn(verified);
		when(verified.activationEligible()).thenReturn(true);
		when(fixture.replayer.replay(staged, verified))
				.thenThrow(new IllegalStateException("corrupt tail after committed canonical prefix"));

		assertThat(fixture.coordinator.tryBootstrapFreshNode())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_INVALID_OR_UNAVAILABLE);
		verify(fixture.replayer).replay(staged, verified);
		verify(fixture.importer, never()).prepare(any(), any());
		verify(fixture.activator, never()).activateCanonical(any());
	}

	private static final class Fixture {

		private final SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		private final ChainQuery chainQuery = mock(ChainQuery.class);
		private final HttpCheckpointSnapshotClient httpClient = mock(HttpCheckpointSnapshotClient.class);
		private final CoreSnapshotArchiveVerifier verifier = mock(CoreSnapshotArchiveVerifier.class);
		private final CoreSnapshotArchiveImporter importer = mock(CoreSnapshotArchiveImporter.class);
		private final CoreSnapshotCanonicalActivator activator = mock(CoreSnapshotCanonicalActivator.class);
		private final CoreSnapshotArchiveReplayer replayer = mock(CoreSnapshotArchiveReplayer.class);
		private final CheckpointRegistry checkpointRegistry = mock(CheckpointRegistry.class);
		private final AuthoritativeChainIdentityProvider chainIdentityProvider =
				mock(AuthoritativeChainIdentityProvider.class);
		private final CoreSnapshotBootstrapCoordinator coordinator;

		private Fixture(boolean enabled, boolean pipelineAvailable) {
			this(enabled, pipelineAvailable, pipelineAvailable);
		}

		private Fixture(boolean enabled, boolean verifierAvailable, boolean atomicPipelineAvailable) {
			properties.setBootstrapEnabled(enabled);
			when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(0L));
			when(checkpointRegistry.hasConfiguredCheckpoints()).thenReturn(true);
			when(chainIdentityProvider.identity()).thenReturn(new StoredChainIdentity(
					1, 0, "mainnet",
					"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f", null));
			coordinator = new CoreSnapshotBootstrapCoordinator(
					properties,
					chainQuery,
					httpClient,
					provider(verifierAvailable ? verifier : null),
					provider(atomicPipelineAvailable ? importer : null),
					provider(atomicPipelineAvailable ? activator : null),
					replayer,
					checkpointRegistry,
					chainIdentityProvider);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider(T value) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfUnique()).thenReturn(value);
		return provider;
	}

	private static StagedCoreSnapshotArchiveDownload stagedDownload() {
		StagedCoreSnapshotArchiveDownload staged = mock(StagedCoreSnapshotArchiveDownload.class);
		when(staged.stateSnapshot()).thenReturn(mock(StagedSnapshotDownload.class));
		return staged;
	}
}
