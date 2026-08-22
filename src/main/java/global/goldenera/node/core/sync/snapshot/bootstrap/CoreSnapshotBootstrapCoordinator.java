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

import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityExpectation;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityPreflight;
import global.goldenera.node.core.storage.chainidentity.ExpectedChainIdentityProvider;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.HttpCheckpointSnapshotClient;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import lombok.extern.slf4j.Slf4j;

/** Coordinates a verified snapshot installation while the application RocksDB is still closed. */
@Service
@Slf4j
public class CoreSnapshotBootstrapCoordinator {

	private final SnapshotDistributionProperties properties;
	private final HttpCheckpointSnapshotClient httpClient;
	private final CoreSnapshotArchiveVerifier verifier;
	private final CoreSnapshotArchiveImporter importer;
	private final CoreSnapshotCanonicalActivator activator;
	private final CoreSnapshotFilesystemActivation filesystemActivation;
	private final CheckpointRegistry checkpointRegistry;
	private final ExpectedChainIdentityProvider expectedChainIdentityProvider;
	private final ChainIdentityPreflight chainIdentityPreflight;

	public CoreSnapshotBootstrapCoordinator(
			SnapshotDistributionProperties properties,
			HttpCheckpointSnapshotClient httpClient,
			CoreSnapshotArchiveVerifier verifier,
			CoreSnapshotArchiveImporter importer,
			CoreSnapshotCanonicalActivator activator,
			CoreSnapshotFilesystemActivation filesystemActivation,
			CheckpointRegistry checkpointRegistry,
			ExpectedChainIdentityProvider expectedChainIdentityProvider,
			ChainIdentityPreflight chainIdentityPreflight) {
		this.properties = properties;
		this.httpClient = httpClient;
		this.verifier = verifier;
		this.importer = importer;
		this.activator = activator;
		this.filesystemActivation = filesystemActivation;
		this.checkpointRegistry = checkpointRegistry;
		this.expectedChainIdentityProvider = expectedChainIdentityProvider;
		this.chainIdentityPreflight = chainIdentityPreflight;
	}

	public Outcome tryBootstrapBeforeStorageOpen() {
		if (!recoverBeforeInspection()) {
			return Outcome.INELIGIBLE;
		}
		ChainIdentityExpectation expectedIdentity = expectedChainIdentityProvider.expectedIdentity();
		chainIdentityPreflight.inspect(expectedIdentity);
		if (!properties.isBootstrapEnabled()) {
			return Outcome.DISABLED;
		}
		CoreSnapshotFilesystemActivation.TargetState targetState = inspectTarget();
		if (!targetState.eligible()) {
			log.info("CORE SNAPSHOT: Existing populated blockchain database is not eligible for replacement");
			return Outcome.INELIGIBLE;
		}
		if (!checkpointRegistry.hasConfiguredCheckpoints()) {
			log.info("CORE SNAPSHOT: No hardcoded checkpoint is configured; skipping trusted HTTP bootstrap");
			return Outcome.INELIGIBLE;
		}
		if (!expectedIdentity.knownProduction()) {
			log.info("CORE SNAPSHOT: Runtime chain identity is not a frozen production identity; skipping HTTP bootstrap");
			return Outcome.INELIGIBLE;
		}

		StagedCoreSnapshotArchiveDownload staged = null;
		PreparedCoreSnapshotImport prepared = null;
		VerifiedCoreSnapshotArchive verified;
		try {
			staged = httpClient.stageFullArchiveFromFirstTrustedSource();
			verified = verifier.verify(
					staged.archiveManifest(),
					staged.stateSnapshot().domainManifest(),
					staged.stateSnapshot().chunkSource(),
					staged.blockChunkSource(),
					staged.entityChunkSource());
			if (verified == null || !verified.activationEligible()) {
				throw new IllegalStateException("Full archive verifier did not grant activation eligibility");
			}
			prepared = importer.prepare(staged, verified);
			if (prepared == null || prepared.verifiedArchive() != verified) {
				throw new IllegalStateException("Importer did not preserve the verified archive capability");
			}
		} catch (Exception failure) {
			closeQuietly(prepared);
			closeQuietly(staged);
			log.warn("CORE SNAPSHOT: Download/verification/import preparation failed; using v1 sync: {}",
					failure.getMessage());
			return Outcome.FALLBACK_INVALID_OR_UNAVAILABLE;
		}

		try {
			activator.activateCanonical(prepared);
			chainIdentityPreflight.inspect(expectedIdentity);
			log.info("CORE SNAPSHOT: Activated verified full archive at height {}", verified.checkpointHeight());
			return Outcome.ACTIVATED;
		} catch (Exception failure) {
			return recoverAfterActivationFailure(failure);
		} finally {
			closeQuietly(prepared);
			closeQuietly(staged);
		}
	}

	private boolean recoverBeforeInspection() {
		try {
			CoreSnapshotFilesystemActivation.RecoveryOutcome outcome = filesystemActivation.recover();
			if (outcome == CoreSnapshotFilesystemActivation.RecoveryOutcome.SKIPPED_UNSAFE_LEGACY_PATH) {
				log.info("CORE SNAPSHOT: Legacy blockchain path uses a symbolic parent; skipping snapshot bootstrap");
				return false;
			}
			if (outcome != CoreSnapshotFilesystemActivation.RecoveryOutcome.NOTHING_TO_RECOVER) {
				log.info("CORE SNAPSHOT: Recovered interrupted filesystem activation: {}", outcome);
			}
			return true;
		} catch (Exception failure) {
			throw new CoreSnapshotBootstrapActivationException(
					"Snapshot activation recovery failed before RocksDB open", failure);
		}
	}

	private CoreSnapshotFilesystemActivation.TargetState inspectTarget() {
		try {
			return filesystemActivation.inspectTargetState();
		} catch (Exception failure) {
			throw new CoreSnapshotBootstrapActivationException(
					"Cannot safely inspect the blockchain database before snapshot bootstrap", failure);
		}
	}

	private Outcome recoverAfterActivationFailure(Exception activationFailure) {
		if (!filesystemActivation.hasRecoveryJournal()) {
			CoreSnapshotFilesystemActivation.TargetState targetState = inspectTarget();
			if (targetState.eligible()) {
				log.warn("CORE SNAPSHOT: Activation failed before the durable journal; using v1 sync: {}",
						activationFailure.getMessage());
				return Outcome.FALLBACK_INVALID_OR_UNAVAILABLE;
			}
			throw new CoreSnapshotBootstrapActivationException(
					"Snapshot activation changed the target without a recoverable journal", activationFailure);
		}
		try {
			CoreSnapshotFilesystemActivation.RecoveryOutcome outcome = filesystemActivation.recover();
			if (outcome == CoreSnapshotFilesystemActivation.RecoveryOutcome.COMPLETED_INSTALLATION) {
				log.warn("CORE SNAPSHOT: Activation failed but recovery completed the verified installation");
				return Outcome.ACTIVATED_RECOVERED;
			}
			if (outcome == CoreSnapshotFilesystemActivation.RecoveryOutcome.RESTORED_ORIGINAL
					&& inspectTarget().eligible()) {
				log.warn("CORE SNAPSHOT: Activation failed and recovery restored the eligible original target; "
						+ "using v1 sync");
				return Outcome.FALLBACK_INVALID_OR_UNAVAILABLE;
			}
		} catch (Exception recoveryFailure) {
			activationFailure.addSuppressed(recoveryFailure);
		}
		throw new CoreSnapshotBootstrapActivationException(
				"Full core snapshot activation could not be recovered safely", activationFailure);
	}

	private void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception e) {
			log.warn("CORE SNAPSHOT: Failed to clean staging resource: {}", e.getMessage());
		}
	}

	public enum Outcome {
		DISABLED,
		INELIGIBLE,
		FALLBACK_INVALID_OR_UNAVAILABLE,
		ACTIVATED,
		ACTIVATED_RECOVERED
	}
}
