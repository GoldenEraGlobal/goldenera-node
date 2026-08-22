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
package global.goldenera.node.core.sync.snapshot.operator;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.KnownProductionChainIdentityRegistry;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService.PublicationResult;
import global.goldenera.node.shared.properties.GeneralProperties;

/** Captures and publishes one manifest-bound canonical anchor from a live node. */
public final class OfflineSnapshotOperatorService {

	private final OfflineSnapshotOperatorProperties properties;
	private final SnapshotDistributionProperties distributionProperties;
	private final GeneralProperties generalProperties;
	private final NetworkSettingsProvider networkSettingsProvider;
	private final AuthoritativeChainIdentityProvider identityProvider;
	private final LiveHeadCoreSnapshotCloneService cloneService;
	private final IsolatedLiveHeadSnapshotPublisher isolatedPublisher;

	public OfflineSnapshotOperatorService(
			OfflineSnapshotOperatorProperties properties,
			SnapshotDistributionProperties distributionProperties,
			GeneralProperties generalProperties,
			NetworkSettingsProvider networkSettingsProvider,
			AuthoritativeChainIdentityProvider identityProvider,
			LiveHeadCoreSnapshotCloneService cloneService,
			IsolatedLiveHeadSnapshotPublisher isolatedPublisher) {
		this.properties = Objects.requireNonNull(properties, "properties");
		this.distributionProperties = Objects.requireNonNull(distributionProperties, "distributionProperties");
		this.generalProperties = Objects.requireNonNull(generalProperties, "generalProperties");
		this.networkSettingsProvider = Objects.requireNonNull(networkSettingsProvider, "networkSettingsProvider");
		this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
		this.cloneService = Objects.requireNonNull(cloneService, "cloneService");
		this.isolatedPublisher = Objects.requireNonNull(isolatedPublisher, "isolatedPublisher");
	}

	public PublicationResult publish() {
		properties.validate(distributionProperties.isAllowHttpForTesting());
		StoredChainIdentity identity = identityProvider.identity();
		if (!"prod".equals(networkSettingsProvider.currentProfile())
				|| !KnownProductionChainIdentityRegistry.isKnownProductionIdentity(identity)) {
			throw new IllegalStateException("Snapshot publication requires an exact known production identity");
		}
		if (properties.isIncludeExplorer()
				&& (!generalProperties.isExplorerEnable() || !generalProperties.isPostgresqlEnable())) {
			throw new IllegalStateException("Explorer snapshot publication requires an enabled PostgreSQL explorer");
		}
		try (LiveHeadCoreSnapshotClone clone = properties.getCheckpointHeight() < 0
				? cloneService.create()
				: cloneService.create(properties.getCheckpointHeight(), null)) {
			if (!clone.identity().equals(identity)) {
				throw new IllegalStateException("Captured clone identity differs from the live production identity");
			}
			PublicationResult result = isolatedPublisher.publish(clone, properties);
			if (!cloneService.isStillCanonical(clone.height(), clone.hash())) {
				cleanupOrphanedPublication(result.publicationDirectory());
				throw new IllegalStateException(
						"Live canonical anchor changed while the isolated snapshot was exported");
			}
			return result;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Live snapshot publication failed", e);
		}
	}

	private void cleanupOrphanedPublication(Path publicationDirectory) {
		Path expected = properties.getOutputDirectory().toAbsolutePath().normalize();
		if (publicationDirectory == null || !publicationDirectory.toAbsolutePath().normalize().equals(expected)
				|| Files.notExists(expected, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(expected)) {
			return;
		}
		try (var paths = Files.walk(expected)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (Exception ignored) {
			// The orphaned artifact remains unselected; operator failure is authoritative.
		}
	}
}
