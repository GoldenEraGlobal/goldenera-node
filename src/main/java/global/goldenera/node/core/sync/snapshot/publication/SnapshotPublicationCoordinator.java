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
package global.goldenera.node.core.sync.snapshot.publication;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.publication.SnapshotPublicationStore.LockUnavailableException;
import global.goldenera.node.shared.properties.GeneralProperties;

/** One locked, idempotent automatic publication evaluation. */
public final class SnapshotPublicationCoordinator {

	private final SnapshotDistributionProperties properties;
	private final GeneralProperties generalProperties;
	private final ChainQuery chainQuery;
	private final ObjectProvider<CoreSnapshotPublicationGenerator> coreGenerator;
	private final ObjectProvider<ExplorerSnapshotPublicationGenerator> explorerGenerator;
	private final SnapshotPublicationStore store;
	private final SnapshotPublicationAnchorPolicy anchorPolicy;
	private final Clock clock;

	public SnapshotPublicationCoordinator(
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			ChainQuery chainQuery,
			ObjectProvider<CoreSnapshotPublicationGenerator> coreGenerator,
			ObjectProvider<ExplorerSnapshotPublicationGenerator> explorerGenerator,
			SnapshotPublicationStore store,
			SnapshotPublicationAnchorPolicy anchorPolicy,
			Clock clock) {
		this.properties = properties;
		this.generalProperties = generalProperties;
		this.chainQuery = chainQuery;
		this.coreGenerator = coreGenerator;
		this.explorerGenerator = explorerGenerator;
		this.store = store;
		this.anchorPolicy = anchorPolicy;
		this.clock = clock;
	}

	public AttemptResult attempt() {
		if (!properties.isPublishEnabled()) {
			return new AttemptResult(Outcome.DISABLED, Duration.ZERO, -1);
		}
		try {
			return store.withLock(this::attemptUnderLock);
		} catch (LockUnavailableException e) {
			return new AttemptResult(Outcome.LOCKED, properties.getPublishRetryInitialBackoff(), -1);
		} catch (Exception e) {
			return failure(e);
		}
	}

	private AttemptResult attemptUnderLock() throws Exception {
		Instant now = clock.instant();
		StoredBlock head = chainQuery.getLatestStoredBlockOrThrow();
		Optional<SnapshotPublicationStore.PublishedSnapshot> current = store.current();
		SnapshotPublicationStore.PublisherState state = store.loadState();
		if (current.isPresent() && !isCanonical(current.orElseThrow())) {
			store.withdrawCurrent();
			current = Optional.empty();
		}
		if (state.nextRetryAtMillis() > now.toEpochMilli()) {
			return new AttemptResult(Outcome.BACKING_OFF,
					Duration.ofMillis(state.nextRetryAtMillis() - now.toEpochMilli()), head.getHeight());
		}
		Optional<SnapshotPublicationAnchor> selectedAnchor = anchorPolicy.select(head);
		if (selectedAnchor.isEmpty()) {
			return new AttemptResult(Outcome.HEAD_BELOW_SAFETY_LAG, Duration.ZERO, head.getHeight());
		}
		SnapshotPublicationAnchor anchor = preferredExplorerAnchor(selectedAnchor.orElseThrow());
		if (current.isPresent() && !cadenceReached(current.orElseThrow(), state, now)) {
			return new AttemptResult(Outcome.CADENCE_NOT_REACHED, Duration.ZERO, head.getHeight());
		}

		store.cleanupStaleGenerations();
		Path generation = store.createGenerationDirectory();
		try {
			Path ready = generation.resolve("ready");
			CoreSnapshotPublicationGenerator generator = coreGenerator.getIfAvailable();
			if (generator == null) {
				throw new IllegalStateException("Core snapshot publication generator is unavailable");
			}
			VerifiedCorePublication verified = generator.generate(anchor.height(), anchor.hash(), ready);
			validateCoreCapability(verified, anchor, ready);
			attemptExplorer(verified, generation, ready);
			StoredBlock canonical = chainQuery.getStoredBlockByHeight(verified.height()).orElse(null);
			if (canonical == null || !canonical.getHash().equals(verified.blockHash())) {
				throw new IllegalStateException("Generated snapshot head is no longer canonical");
			}
			store.publish(ready, verified.height(), verified.blockHash(), now);
			store.saveState(new SnapshotPublicationStore.PublisherState(
					verified.height(), now.toEpochMilli(), 0, 0));
			return new AttemptResult(Outcome.PUBLISHED, Duration.ZERO, verified.height());
		} catch (Exception e) {
			return failure(e);
		} finally {
			store.cleanup(generation);
		}
	}

	private boolean isCanonical(SnapshotPublicationStore.PublishedSnapshot current) {
		StoredBlock canonical = chainQuery.getStoredBlockByHeight(current.height()).orElse(null);
		return canonical != null && canonical.getHash().equals(current.hash());
	}

	private SnapshotPublicationAnchor preferredExplorerAnchor(SnapshotPublicationAnchor maximumSafeAnchor) {
		if (!generalProperties.isExplorerEnable()) {
			return maximumSafeAnchor;
		}
		ExplorerSnapshotPublicationGenerator generator = explorerGenerator.getIfAvailable();
		if (generator == null) {
			return maximumSafeAnchor;
		}
		try {
			Optional<SnapshotPublicationAnchor> preferred = generator.preferredCoreAnchor(maximumSafeAnchor);
			if (preferred.isEmpty()) {
				return maximumSafeAnchor;
			}
			SnapshotPublicationAnchor anchor = preferred.orElseThrow();
			StoredBlock canonical = anchor.height() <= maximumSafeAnchor.height()
					? chainQuery.getStoredBlockByHeight(anchor.height()).orElse(null) : null;
			return canonical != null && canonical.getHash().equals(anchor.hash())
					? anchor : maximumSafeAnchor;
		} catch (Exception ignored) {
			// Explorer capture selection is optional and cannot block core publication.
			return maximumSafeAnchor;
		}
	}

	private boolean cadenceReached(
			SnapshotPublicationStore.PublishedSnapshot current,
			SnapshotPublicationStore.PublisherState state,
			Instant now) {
		long lastMillis = state.lastPublishedAtMillis() > 0
				? state.lastPublishedAtMillis() : current.publishedAt().toEpochMilli();
		Duration elapsed = Duration.ofMillis(Math.max(0, now.toEpochMilli() - lastMillis));
		return elapsed.compareTo(properties.getPublishCycle()) >= 0;
	}

	private void validateCoreCapability(
			VerifiedCorePublication verified, SnapshotPublicationAnchor anchor, Path ready) {
		if (verified == null || verified.height() != anchor.height()
				|| !verified.blockHash().equals(anchor.hash())
				|| !verified.directory().toAbsolutePath().normalize().equals(ready)
				|| !regular(ready.resolve("manifest.json"))
				|| !regular(ready.resolve("archive-manifest.json"))) {
			throw new IllegalStateException("Core generator did not return the exact verified head capability");
		}
	}

	private void attemptExplorer(VerifiedCorePublication core, Path generation, Path ready) {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		ExplorerSnapshotPublicationGenerator generator = explorerGenerator.getIfAvailable();
		if (generator == null || !generator.isExactlyCaughtUp(core)) {
			return;
		}
			Path explorer = null;
		List<Path> movedTargets = new ArrayList<>();
		try {
			explorer = generation.resolve("explorer");
			generator.generate(core, explorer);
			if (!Files.isDirectory(explorer, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalStateException("Explorer generator did not create its artifact directory");
			}
			try (var files = Files.list(explorer)) {
				for (Path source : files.toList()) {
					Path target = ready.resolve(source.getFileName().toString()).normalize();
					if (!target.getParent().equals(ready) || !regular(source)
							|| Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
						throw new IllegalStateException("Explorer generator produced an unsafe artifact");
					}
					Files.move(source, target, ATOMIC_MOVE);
					movedTargets.add(target);
				}
			}
		} catch (Exception ignored) {
			// Explorer data is optional and cannot invalidate the verified core artifact.
			for (int index = movedTargets.size() - 1; index >= 0; index--) {
				try {
					Files.deleteIfExists(movedTargets.get(index));
				} catch (Exception cleanupFailure) {
					// Core manifests remain valid and explorer artifacts are never required.
				}
			}
		} finally {
			store.cleanup(explorer);
		}
	}

	private AttemptResult failure(Exception failure) {
		try {
			SnapshotPublicationStore.PublisherState previous = store.loadState();
			int failures = Math.min(30, previous.failures() + 1);
			Duration delay = retryDelay(failures);
			store.saveState(new SnapshotPublicationStore.PublisherState(
					previous.lastPublishedHeight(), previous.lastPublishedAtMillis(), failures,
					clock.instant().plus(delay).toEpochMilli()));
			return new AttemptResult(Outcome.RETRY_REQUIRED, delay, previous.lastPublishedHeight());
		} catch (Exception stateFailure) {
			failure.addSuppressed(stateFailure);
			return new AttemptResult(Outcome.RETRY_REQUIRED,
					properties.getPublishRetryMaxBackoff(), -1);
		}
	}

	private Duration retryDelay(int failures) {
		long multiplier = 1L << Math.min(20, Math.max(0, failures - 1));
		Duration initial = properties.getPublishRetryInitialBackoff();
		Duration maximum = properties.getPublishRetryMaxBackoff();
		try {
			Duration calculated = initial.multipliedBy(multiplier);
			return calculated.compareTo(maximum) > 0 ? maximum : calculated;
		} catch (ArithmeticException e) {
			return maximum;
		}
	}

	private boolean regular(Path path) {
		return !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
	}

	public enum Outcome {
		DISABLED,
		HEAD_BELOW_SAFETY_LAG,
		BACKING_OFF,
		LOCKED,
		CADENCE_NOT_REACHED,
		PUBLISHED,
		RETRY_REQUIRED
	}

	public record AttemptResult(Outcome outcome, Duration retryAfter, long observedHeight) {
	}
}
