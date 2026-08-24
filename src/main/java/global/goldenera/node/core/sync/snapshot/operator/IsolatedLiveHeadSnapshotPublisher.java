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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import global.goldenera.node.Application;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService.PublicationResult;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;

/** Opens the closed Rocks clone in a separate non-live Spring context for export. */
public final class IsolatedLiveHeadSnapshotPublisher {

	public PublicationResult publish(
			LiveHeadCoreSnapshotClone clone,
			OfflineSnapshotOperatorProperties properties) throws Exception {
		return publish(clone, properties, properties.getOutputDirectory());
	}

	public PublicationResult publish(
			LiveHeadCoreSnapshotClone clone,
			OfflineSnapshotOperatorProperties properties,
			Path outputDirectory) throws Exception {
		return publish(clone, properties, outputDirectory, properties.isIncludeExplorer());
	}

	public PublicationResult publish(
			LiveHeadCoreSnapshotClone clone,
			OfflineSnapshotOperatorProperties properties,
			Path outputDirectory,
			boolean includeExplorer) throws Exception {
		Path peerDatabase = Files.createTempDirectory(
				clone.databaseDirectory().getParent(), ".snapshot-peer-reputation-").toRealPath();
		LiveHeadCloneExportCapability capability = LiveHeadCloneExportCapability.from(clone);
		try (ConfigurableApplicationContext context = exportApplication(capability)
				.run(
						"--ge.snapshot.operator.enabled=true",
						"--ge.snapshot.operator.clone-export-context=true",
						"--ge.snapshot.operator.suppress-runtime=true",
						"--ge.snapshot.operator.include-explorer=" + includeExplorer,
						"--ge.snapshot.operator.checkpoint-height=" + clone.height(),
						"--ge.snapshot.operator.output-directory=" + outputDirectory,
						"--ge.snapshot.operator.public-origin=" + properties.getPublicOrigin(),
						"--ge.snapshot.operator.explorer-chunk-bytes=" + properties.getExplorerChunkBytes(),
						"--ge.core.blockchain.db.path=" + clone.databaseDirectory(),
						"--ge.core.blockchain.db.rocksdb-block-cache-mb="
								+ SnapshotCloneResourceLimits.ROCKS_DB_BLOCK_CACHE_MB,
						"--ge.core.blockchain.db.rocksdb-write-buffer-mb="
								+ SnapshotCloneResourceLimits.ROCKS_DB_WRITE_BUFFER_MB,
						"--ge.core.blockchain.db.rocksdb-max-write-buffers="
								+ SnapshotCloneResourceLimits.ROCKS_DB_MAX_WRITE_BUFFERS,
						"--ge.core.blockchain.db.cache-block-mb="
								+ SnapshotCloneResourceLimits.BLOCK_CACHE_MB,
						"--ge.core.blockchain.db.cache-trie-node-mb="
								+ SnapshotCloneResourceLimits.TRIE_NODE_CACHE_MB,
						"--ge.core.blockchain.db.cache-tx-mb="
								+ SnapshotCloneResourceLimits.TX_CACHE_MB,
						"--ge.core.blockchain.db.cache-header-max-entries="
								+ SnapshotCloneResourceLimits.INDEX_CACHE_ENTRIES,
						"--ge.core.blockchain.db.cache-height-max-entries="
								+ SnapshotCloneResourceLimits.INDEX_CACHE_ENTRIES,
						"--ge.core.peer-reputation.db.path=" + peerDatabase,
						"--ge.core.sync.snapshot.bootstrap-enabled=false",
						"--ge.core.sync.snapshot.publish-enabled=false",
						"--ge.core.mining.enable=false",
						"--ge.general.explorer-enable=" + includeExplorer,
						"--ge.general.postgresql-enable=" + includeExplorer,
						"--ge.general.webhook-enable=false")) {
			CheckpointSnapshotPublicationService publication =
					context.getBean(CheckpointSnapshotPublicationService.class);
			PublicationResult result = includeExplorer
					? publication.prepareCombinedWithExplorer(
							clone.height(), outputDirectory,
							context.getBean(ExplorerCheckpointSnapshotExporter.class),
							properties.getExplorerChunkBytes())
					: publication.prepareCombined(clone.height(), outputDirectory);
			if (result.stateManifest().checkpointHeight() != clone.height()
					|| !result.stateManifest().checkpointHash().equals(clone.hash())
					|| !result.stateManifest().checkpointStateRoot().equals(clone.stateRoot())
					|| !result.stateManifest().checkpointCumulativeDifficulty()
							.equals(clone.cumulativeDifficulty())
					|| !result.stateManifest().chainIdentity().equals(clone.identity())) {
				throw new IllegalStateException("Isolated export does not match the captured live head binding");
			}
			return result;
		} finally {
			deleteDirectory(peerDatabase);
		}
	}

	SpringApplicationBuilder exportApplication(LiveHeadCloneExportCapability capability) {
		return new SpringApplicationBuilder(Application.class)
				.web(WebApplicationType.NONE)
				.lazyInitialization(true)
				.bannerMode(Banner.Mode.OFF)
				.logStartupInfo(false)
				.initializers(applicationContext -> applicationContext.getBeanFactory().registerSingleton(
						LiveHeadCloneExportCapability.BEAN_NAME, capability));
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (Files.notExists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
