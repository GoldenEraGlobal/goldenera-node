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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.KNOWN_PRODUCTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityExpectation;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityPreflight;
import global.goldenera.node.core.storage.chainidentity.ExpectedChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveChunkSource;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkSource;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.HttpCheckpointSnapshotClient;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import global.goldenera.node.core.sync.snapshot.transport.StagedSnapshotDownload;

class CoreSnapshotBootstrapCoordinatorTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void populatedLegacyTargetSkipsHttpAndRemainsUntouched() throws Exception {
		Path target = temporaryDirectory.resolve("legacy-blockchain");
		Files.createDirectory(target);
		Path legacyFile = target.resolve("legacy-data");
		Files.writeString(legacyFile, "preserve-me");
		Fixture fixture = new Fixture(target);

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);

		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
		assertThat(Files.readString(legacyFile)).isEqualTo("preserve-me");
	}

	@Test
	void symlinkedLegacyParentSkipsSnapshotAndLeavesCorePathUntouched() throws Exception {
		Path realParent = Files.createDirectory(temporaryDirectory.resolve("real-parent"));
		Path realTarget = Files.createDirectory(realParent.resolve("blockchain"));
		Files.writeString(realTarget.resolve("legacy-data"), "preserve-me");
		Path linkedParent = temporaryDirectory.resolve("linked-parent");
		try {
			Files.createSymbolicLink(linkedParent, realParent);
		} catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
		}
		Fixture fixture = new Fixture(linkedParent.resolve("blockchain"));

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		assertThat(realTarget.resolve("legacy-data")).hasContent("preserve-me");
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void disabledBootstrapStillRunsRecoveryAndNeverDownloads() {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));
		fixture.properties.setBootstrapEnabled(false);

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.DISABLED);

		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void explorerEnabledDoesNotBlockCoreFastPath() {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_INVALID_OR_UNAVAILABLE);

		verify(fixture.httpClient).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void missingHardcodedCheckpointSkipsHttp() {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));
		when(fixture.checkpointRegistry.hasConfiguredCheckpoints()).thenReturn(false);

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void nonProductionIdentitySkipsTrustedHttp() {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));
		StoredChainIdentity developmentIdentity = new StoredChainIdentity(
				1, 0, "development-mainnet", "0x" + "01".repeat(32), null);
		when(fixture.expectedIdentityProvider.expectedIdentity())
				.thenReturn(new ChainIdentityExpectation(developmentIdentity, DEVELOPMENT));

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.INELIGIBLE);
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	@Test
	void explorerDisabledUsesFiveSourceVerifierAndActivatesPreparedDatabase() throws Exception {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));
		fixture.stubVerifiedPipeline();

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.ACTIVATED);

		verify(fixture.verifier).verify(
				same(fixture.archiveManifest), same(fixture.stateManifest), same(fixture.stateChunks),
				same(fixture.blockChunks), same(fixture.entityChunks));
		verify(fixture.importer).prepare(fixture.staged, fixture.verified);
		verify(fixture.activator).activateCanonical(fixture.prepared);
	}

	@Test
	void invalidDownloadLeavesEligibleTargetUntouchedAndFallsBackToP2p() throws Exception {
		Path target = temporaryDirectory.resolve("blockchain");
		Fixture fixture = new Fixture(target);
		when(fixture.httpClient.stageFullArchiveFromFirstTrustedSource())
				.thenThrow(new IllegalStateException("unavailable"));

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_INVALID_OR_UNAVAILABLE);

		assertThat(target).doesNotExist();
		verify(fixture.activator, never()).activateCanonical(fixture.prepared);
	}

	@Test
	void failureBeforeActivationJournalAllowsP2pFallback() throws Exception {
		Fixture fixture = new Fixture(temporaryDirectory.resolve("blockchain"));
		fixture.stubVerifiedPipeline();
		doThrow(new IllegalStateException("before journal"))
				.when(fixture.activator).activateCanonical(fixture.prepared);

		assertThat(fixture.coordinator.tryBootstrapBeforeStorageOpen())
				.isEqualTo(CoreSnapshotBootstrapCoordinator.Outcome.FALLBACK_INVALID_OR_UNAVAILABLE);
	}

	@Test
	void malformedRecoveryJournalFailsClosedBeforeHttp() throws Exception {
		Path target = temporaryDirectory.resolve("blockchain");
		Path journal = temporaryDirectory.resolve(".blockchain.snapshot-activation-v1.journal");
		Files.writeString(journal, "malformed");
		Fixture fixture = new Fixture(target);

		assertThatThrownBy(fixture.coordinator::tryBootstrapBeforeStorageOpen)
				.isInstanceOf(CoreSnapshotBootstrapActivationException.class)
				.hasMessageContaining("recovery failed");
		verify(fixture.httpClient, never()).stageFullArchiveFromFirstTrustedSource();
	}

	private static final class Fixture {

		private final SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		private final HttpCheckpointSnapshotClient httpClient = mock(HttpCheckpointSnapshotClient.class);
		private final CoreSnapshotArchiveVerifier verifier = mock(CoreSnapshotArchiveVerifier.class);
		private final CoreSnapshotArchiveImporter importer = mock(CoreSnapshotArchiveImporter.class);
		private final CoreSnapshotCanonicalActivator activator = mock(CoreSnapshotCanonicalActivator.class);
		private final CheckpointRegistry checkpointRegistry = mock(CheckpointRegistry.class);
		private final ExpectedChainIdentityProvider expectedIdentityProvider = mock(ExpectedChainIdentityProvider.class);
		private final ChainIdentityPreflight chainIdentityPreflight = mock(ChainIdentityPreflight.class);
		private final CoreSnapshotBootstrapCoordinator coordinator;
		private final StagedCoreSnapshotArchiveDownload staged = mock(StagedCoreSnapshotArchiveDownload.class);
		private final StagedSnapshotDownload stateDownload = mock(StagedSnapshotDownload.class);
		private final CheckpointSnapshotManifest stateManifest = mock(CheckpointSnapshotManifest.class);
		private final CoreSnapshotArchiveManifest archiveManifest = mock(CoreSnapshotArchiveManifest.class);
		private final SnapshotChunkSource stateChunks = mock(SnapshotChunkSource.class);
		private final CoreSnapshotArchiveChunkSource blockChunks = mock(CoreSnapshotArchiveChunkSource.class);
		private final CoreSnapshotEntityChunkSource entityChunks = mock(CoreSnapshotEntityChunkSource.class);
		private final VerifiedCoreSnapshotArchive verified = mock(VerifiedCoreSnapshotArchive.class);
		private final PreparedCoreSnapshotImport prepared = mock(PreparedCoreSnapshotImport.class);

		private Fixture(Path target) {
			properties.setBootstrapEnabled(true);
			when(checkpointRegistry.hasConfiguredCheckpoints()).thenReturn(true);
			StoredChainIdentity identity = new StoredChainIdentity(
					1, 0, "mainnet",
					"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f", null);
			when(expectedIdentityProvider.expectedIdentity())
					.thenReturn(new ChainIdentityExpectation(identity, KNOWN_PRODUCTION));
			CoreSnapshotFilesystemActivation filesystemActivation =
					new CoreSnapshotFilesystemActivation(target.toAbsolutePath().normalize());
			coordinator = new CoreSnapshotBootstrapCoordinator(
					properties, httpClient, verifier, importer, activator,
					filesystemActivation, checkpointRegistry, expectedIdentityProvider, chainIdentityPreflight);
		}

		private void stubVerifiedPipeline() throws Exception {
			when(httpClient.stageFullArchiveFromFirstTrustedSource()).thenReturn(staged);
			when(staged.stateSnapshot()).thenReturn(stateDownload);
			when(stateDownload.domainManifest()).thenReturn(stateManifest);
			when(stateDownload.chunkSource()).thenReturn(stateChunks);
			when(staged.archiveManifest()).thenReturn(archiveManifest);
			when(staged.blockChunkSource()).thenReturn(blockChunks);
			when(staged.entityChunkSource()).thenReturn(entityChunks);
			when(verifier.verify(archiveManifest, stateManifest, stateChunks, blockChunks, entityChunks))
					.thenReturn(verified);
			when(verified.activationEligible()).thenReturn(true);
			when(verified.checkpointHeight()).thenReturn(123L);
			when(importer.prepare(staged, verified)).thenReturn(prepared);
			when(prepared.verifiedArchive()).thenReturn(verified);
		}
	}
}
