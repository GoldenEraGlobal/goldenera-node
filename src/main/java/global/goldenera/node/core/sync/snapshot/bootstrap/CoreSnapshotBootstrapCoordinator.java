/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.KnownProductionChainIdentityRegistry;
import global.goldenera.node.core.sync.CoreSnapshotArchiveReplayer;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.HttpCheckpointSnapshotClient;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import lombok.extern.slf4j.Slf4j;

/** Opt-in startup orchestration. No P2P or live storage mutation occurs before activation. */
@Service
@Slf4j
public class CoreSnapshotBootstrapCoordinator {

	private static final long GENESIS_HEIGHT = 0L;

	private final SnapshotDistributionProperties properties;
	private final ChainQuery chainQuery;
	private final HttpCheckpointSnapshotClient httpClient;
	private final ObjectProvider<CoreSnapshotArchiveVerifier> verifierProvider;
	private final ObjectProvider<CoreSnapshotArchiveImporter> importerProvider;
	private final ObjectProvider<CoreSnapshotCanonicalActivator> activatorProvider;
	private final CoreSnapshotArchiveReplayer archiveReplayer;
	private final CheckpointRegistry checkpointRegistry;
	private final AuthoritativeChainIdentityProvider chainIdentityProvider;

	public CoreSnapshotBootstrapCoordinator(
			SnapshotDistributionProperties properties,
			ChainQuery chainQuery,
			HttpCheckpointSnapshotClient httpClient,
			ObjectProvider<CoreSnapshotArchiveVerifier> verifierProvider,
			ObjectProvider<CoreSnapshotArchiveImporter> importerProvider,
			ObjectProvider<CoreSnapshotCanonicalActivator> activatorProvider,
			CoreSnapshotArchiveReplayer archiveReplayer,
			CheckpointRegistry checkpointRegistry,
			AuthoritativeChainIdentityProvider chainIdentityProvider) {
		this.properties = properties;
		this.chainQuery = chainQuery;
		this.httpClient = httpClient;
		this.verifierProvider = verifierProvider;
		this.importerProvider = importerProvider;
		this.activatorProvider = activatorProvider;
		this.archiveReplayer = archiveReplayer;
		this.checkpointRegistry = checkpointRegistry;
		this.chainIdentityProvider = chainIdentityProvider;
	}

	public Outcome tryBootstrapFreshNode() {
		if (!properties.isBootstrapEnabled()) {
			return Outcome.DISABLED;
		}
		Optional<Long> currentHeight = chainQuery.getLatestBlockHeight();
		if (currentHeight.isEmpty() || currentHeight.get() != GENESIS_HEIGHT) {
			log.info("CORE SNAPSHOT: Skipping bootstrap for non-genesis chain at height {}",
					currentHeight.map(String::valueOf).orElse("unknown"));
			return Outcome.INELIGIBLE;
		}
		if (!checkpointRegistry.hasConfiguredCheckpoints()) {
			log.info("CORE SNAPSHOT: No hardcoded checkpoint is configured; skipping trusted HTTP bootstrap");
			return Outcome.INELIGIBLE;
		}
		if (!KnownProductionChainIdentityRegistry.isKnownProductionIdentity(chainIdentityProvider.identity())) {
			log.info("CORE SNAPSHOT: Runtime chain identity is not a frozen production identity; skipping HTTP bootstrap");
			return Outcome.INELIGIBLE;
		}

		CoreSnapshotArchiveVerifier verifier = verifierProvider.getIfUnique();
		CoreSnapshotArchiveImporter importer = importerProvider.getIfUnique();
		CoreSnapshotCanonicalActivator activator = activatorProvider.getIfUnique();
		if (verifier == null) {
			log.warn("CORE SNAPSHOT: Full archive verifier is unavailable; using v1 sync");
			return Outcome.FALLBACK_PIPELINE_UNAVAILABLE;
		}
		boolean atomicPipelineAvailable = importer != null && activator != null;

		StagedCoreSnapshotArchiveDownload staged = null;
		PreparedCoreSnapshotImport prepared = null;
		VerifiedCoreSnapshotArchive verified;
		try {
			staged = httpClient.stageFullArchiveFromFirstTrustedSource();
			verified = verifier.verify(
					staged.archiveManifest(),
					staged.stateSnapshot().domainManifest(),
					staged.stateSnapshot().chunkSource(),
					staged.blockChunkSource());
			if (verified == null || !verified.activationEligible()) {
				throw new IllegalStateException("Full archive verifier did not grant activation eligibility");
			}
			if (!atomicPipelineAvailable) {
				archiveReplayer.replay(staged, verified);
				log.info("CORE SNAPSHOT: Full archive replay reached verified checkpoint {}",
						verified.checkpointHeight());
				closeQuietly(staged);
				staged = null;
				return Outcome.REPLAYED;
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
			StoredBlock activatedHead = chainQuery.getLatestStoredBlockOrThrow();
			if (activatedHead.getHeight() != verified.checkpointHeight()
					|| !activatedHead.getHash().equals(verified.checkpointHash())) {
				throw new IllegalStateException("Activated canonical head does not match verified checkpoint");
			}
			log.info("CORE SNAPSHOT: Activated verified full archive at height {}", verified.checkpointHeight());
			return Outcome.ACTIVATED;
		} catch (Exception failure) {
			throw new CoreSnapshotBootstrapActivationException(
					"Full core snapshot activation failed; refusing unsafe v1 fallback", failure);
		} finally {
			closeQuietly(prepared);
			closeQuietly(staged);
		}
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
		FALLBACK_PIPELINE_UNAVAILABLE,
		FALLBACK_INVALID_OR_UNAVAILABLE,
		REPLAYED,
		ACTIVATED
	}
}
