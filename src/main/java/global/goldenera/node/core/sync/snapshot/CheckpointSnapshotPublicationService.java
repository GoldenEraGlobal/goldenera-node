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
package global.goldenera.node.core.sync.snapshot;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.CheckpointStateSnapshotExporter.ExportResult;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveExporter;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.BinarySnapshotNodeSource;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotArtifactExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotBinding;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotChunkDescriptor;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotManifest;

/**
 * Operator-invoked offline publication workflow. Generation never runs from an
 * HTTP handler; the controller only observes the final immutable directory
 * after a same-filesystem atomic rename.
 */
public final class CheckpointSnapshotPublicationService {

	private final CheckpointStateSnapshotExporter stateExporter;
	private final CoreSnapshotArchiveExporter archiveExporter;
	private final CoreSnapshotArchiveVerifier fullVerifier;

	public CheckpointSnapshotPublicationService(
			CheckpointStateSnapshotExporter stateExporter,
			CoreSnapshotArchiveExporter archiveExporter,
			CoreSnapshotArchiveVerifier fullVerifier) {
		this.stateExporter = Objects.requireNonNull(stateExporter, "stateExporter");
		this.archiveExporter = Objects.requireNonNull(archiveExporter, "archiveExporter");
		this.fullVerifier = Objects.requireNonNull(fullVerifier, "fullVerifier");
	}

	/**
	 * Prepares, fully verifies and atomically publishes a combined state + FULL
	 * archive artifact set. The target must be an absolute normalized path which
	 * does not yet exist. Existing publications are never replaced.
	 */
	public PublicationResult prepareCombined(long checkpointHeight, Path publishDirectory) {
		return prepareCombined(checkpointHeight, publishDirectory, null, 0, false);
	}

	/**
	 * Publishes explorer data in the same atomic artifact set after binding its
	 * export to the core state and archive manifests created by this invocation.
	 */
	public PublicationResult prepareCombinedWithExplorer(
			long checkpointHeight,
			Path publishDirectory,
			ExplorerCheckpointSnapshotExporter explorerExporter,
			int explorerChunkBytes) {
		Objects.requireNonNull(explorerExporter, "explorerExporter");
		return prepareCombined(checkpointHeight, publishDirectory, explorerExporter, explorerChunkBytes, false);
	}

	/**
	 * Attempts to attach explorer data at the exact core snapshot head. Explorer
	 * lag/unavailability omits only that optional artifact; verified core artifacts
	 * are still published. When explorer is disabled the exporter is never touched.
	 */
	public PublicationResult prepareCombinedWithOptionalExplorer(
			long checkpointHeight,
			Path publishDirectory,
			boolean explorerEnabled,
			ExplorerSnapshotArtifactExporter explorerExporter,
			int explorerChunkBytes) {
		if (!explorerEnabled) {
			return prepareCombined(checkpointHeight, publishDirectory, null, 0, false);
		}
		Objects.requireNonNull(explorerExporter, "explorerExporter");
		return prepareCombined(checkpointHeight, publishDirectory, explorerExporter, explorerChunkBytes, true);
	}

	private PublicationResult prepareCombined(
			long checkpointHeight,
			Path publishDirectory,
			ExplorerSnapshotArtifactExporter explorerExporter,
			int explorerChunkBytes,
			boolean optionalExplorer) {
		PublishTarget target = validateMissingPublishTarget(publishDirectory);
		Path stagingRoot = null;
		boolean published = false;
		try {
			stagingRoot = Files.createTempDirectory(
					target.parent(), "." + target.path().getFileName() + "-prepare-").toRealPath();
			Path stateDirectory = Files.createDirectory(stagingRoot.resolve("state"));
			Path archiveDirectory = Files.createDirectory(stagingRoot.resolve("archive"));
			Path readyDirectory = Files.createDirectory(stagingRoot.resolve("ready"));

			ExportResult state = stateExporter.export(checkpointHeight, stateDirectory);
			CoreSnapshotArchiveExporter.ExportResult archive = archiveExporter.export(
					checkpointHeight, state.manifest(), archiveDirectory);
			ExplorerSnapshotManifest explorerManifest = null;
			Path explorerDirectory = null;
			if (explorerExporter != null) {
				ExplorerSnapshotBinding binding = explorerBinding(state, archive);
				explorerDirectory = stagingRoot.resolve("explorer");
				try {
					explorerManifest = explorerExporter.export(binding, explorerDirectory, explorerChunkBytes);
					if (!binding.equals(bindingOf(explorerManifest))) {
						throw new SnapshotExportException(
								"Explorer snapshot is not bound to the generated core manifests");
					}
				} catch (RuntimeException explorerFailure) {
					if (!optionalExplorer) {
						throw explorerFailure;
					}
					deleteDirectoryQuietly(explorerDirectory);
					explorerDirectory = null;
					explorerManifest = null;
				}
			}

			List<Path> stateChunks = moveStateChunks(state, readyDirectory);
			List<Path> archiveChunks = moveArchiveChunks(archive, readyDirectory);
			List<Path> entityChunks = moveEntityChunks(archive, readyDirectory);
			if (explorerManifest != null) {
				moveExplorerArtifacts(explorerDirectory, explorerManifest, readyDirectory);
			}
			// Both manifests are moved only after every chunk is complete. The archive
			// manifest is the last file placed in the ready publication directory.
			moveExact(state.manifestFile(), readyDirectory.resolve("manifest.json"));
			moveExact(archive.manifestFile(), readyDirectory.resolve("archive-manifest.json"));

			VerifiedCoreSnapshotArchive verified = fullVerifier.verify(
					archive.manifest(),
					state.manifest(),
					descriptor -> new BinarySnapshotNodeSource(
							stateChunks.get(descriptor.index()), descriptor),
					descriptor -> Files.newInputStream(
							archiveChunks.get(descriptor.index()), LinkOption.NOFOLLOW_LINKS),
					descriptor -> Files.newInputStream(
							entityChunks.get(descriptor.index()), LinkOption.NOFOLLOW_LINKS));

			PublicationResult result = new PublicationResult(
					target.path(), state.manifest(), archive.manifest(), verified,
					state.manifestSigningHash(), archive.manifestSigningHash(),
					stateChunks.size(), archiveChunks.size(), entityChunks.size(), explorerManifest,
					explorerManifest == null ? 0 : explorerManifest.chunks().size());
			// The operator supplies a unique/versioned path. Re-check immediately before
			// the no-REPLACE atomic rename so a second publication attempt fails closed.
			// Re-resolve every parent component immediately before the rename. This
			// catches a parent-directory/symlink swap as well as a target race.
			validateMissingPublishTarget(target.path());
			Files.move(readyDirectory, target.path(), ATOMIC_MOVE);
			published = true;
			deleteDirectoryQuietly(stagingRoot);
			return result;
		} catch (SnapshotExportException e) {
			if (!published) {
				deleteDirectoryQuietly(stagingRoot);
			}
			throw e;
		} catch (Exception e) {
			if (!published) {
				deleteDirectoryQuietly(stagingRoot);
			}
			throw new SnapshotExportException("Combined checkpoint snapshot publication failed", e);
		}
	}

	private ExplorerSnapshotBinding explorerBinding(
			ExportResult state,
			CoreSnapshotArchiveExporter.ExportResult archive) {
		CheckpointSnapshotManifest manifest = state.manifest();
		return new ExplorerSnapshotBinding(
				manifest.networkCode(), manifest.chainIdentity().chainId(), manifest.chainIdentity().genesisHash(),
				manifest.checkpointHeight(), manifest.checkpointHash().toHexString(),
				manifest.checkpointStateRoot().toHexString(), unprefixed(state.manifestSigningHash()),
				unprefixed(archive.manifestSigningHash()));
	}

	private ExplorerSnapshotBinding bindingOf(ExplorerSnapshotManifest manifest) {
		return new ExplorerSnapshotBinding(
				manifest.carrierNetworkCode(), manifest.chainId(), manifest.genesisHash(), manifest.checkpointHeight(),
				manifest.checkpointHash(), manifest.checkpointStateRoot(), manifest.coreStateSigningHash(),
				manifest.coreArchiveSigningHash());
	}

	private void moveExplorerArtifacts(
			Path sourceDirectory,
			ExplorerSnapshotManifest manifest,
			Path readyDirectory) throws IOException {
		for (ExplorerSnapshotChunkDescriptor descriptor : manifest.chunks()) {
			Path source = sourceDirectory.resolve(descriptor.fileName()).normalize();
			if (!source.getParent().equals(sourceDirectory)) {
				throw new SnapshotExportException("Explorer snapshot chunk path is unsafe");
			}
			moveExact(source, readyDirectory.resolve(descriptor.fileName()));
		}
		moveExact(sourceDirectory.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME),
				readyDirectory.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME));
		Files.delete(sourceDirectory);
	}

	private String unprefixed(Hash hash) {
		String value = hash.toHexString();
		return value.startsWith("0x") ? value.substring(2) : value;
	}

	private List<Path> moveStateChunks(ExportResult state, Path ready) throws IOException {
		if (state.chunkFiles().size() != state.manifest().chunks().size()) {
			throw new SnapshotExportException("State exporter chunk set does not match its manifest");
		}
		List<Path> moved = new ArrayList<>(state.chunkFiles().size());
		for (int index = 0; index < state.chunkFiles().size(); index++) {
			Path target = ready.resolve("chunk-%05d.bin".formatted(index));
			moveExact(state.chunkFiles().get(index), target);
			moved.add(target);
		}
		return List.copyOf(moved);
	}

	private List<Path> moveArchiveChunks(CoreSnapshotArchiveExporter.ExportResult archive, Path ready)
			throws IOException {
		if (archive.chunkFiles().size() != archive.manifest().blockChunks().size()) {
			throw new SnapshotExportException("Archive exporter chunk set does not match its manifest");
		}
		List<Path> moved = new ArrayList<>(archive.chunkFiles().size());
		for (int index = 0; index < archive.chunkFiles().size(); index++) {
			Path target = ready.resolve("archive-chunk-%05d.bin".formatted(index));
			moveExact(archive.chunkFiles().get(index), target);
			moved.add(target);
		}
		return List.copyOf(moved);
	}

	private List<Path> moveEntityChunks(CoreSnapshotArchiveExporter.ExportResult archive, Path ready)
			throws IOException {
		if (archive.entityChunkFiles().size() != archive.manifest().entityChunks().size()) {
			throw new SnapshotExportException("Entity chunk set does not match its archive manifest");
		}
		List<Path> moved = new ArrayList<>(archive.entityChunkFiles().size());
		for (int index = 0; index < archive.entityChunkFiles().size(); index++) {
			CoreSnapshotEntityChunkDescriptor descriptor = archive.manifest().entityChunks().get(index);
			if (descriptor.index() != index) {
				throw new SnapshotExportException("Entity chunk indexes are not contiguous");
			}
			Path target = ready.resolve("entity-chunk-%05d.zst".formatted(index));
			moveExact(archive.entityChunkFiles().get(index), target);
			moved.add(target);
		}
		return List.copyOf(moved);
	}

	private void moveExact(Path source, Path target) throws IOException {
		if (source == null || Files.isSymbolicLink(source)
				|| !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(target, LinkOption.NOFOLLOW_LINKS)
				|| !target.getParent().equals(target.normalize().getParent())) {
			throw new SnapshotExportException("Generated snapshot artifact path is unsafe");
		}
		Files.move(source, target, ATOMIC_MOVE);
	}

	private PublishTarget validateMissingPublishTarget(Path target) {
		Objects.requireNonNull(target, "publishDirectory");
		if (!target.isAbsolute() || !target.equals(target.normalize()) || target.getParent() == null
				|| target.equals(target.getRoot())) {
			throw new SnapshotExportException("Publish target must be a normalized absolute child path");
		}
		Path parent = target.getParent();
		try {
			Path current = parent.getRoot();
			for (Path component : parent) {
				current = current.resolve(component);
				if (Files.isSymbolicLink(current)) {
					throw new SnapshotExportException("Publish target path must not contain symbolic links");
				}
			}
			if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
					|| !parent.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(parent)) {
				throw new SnapshotExportException("Publish target parent must be an existing real directory");
			}
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new SnapshotExportException("Publish target already exists; active snapshots are never replaced");
			}
			return new PublishTarget(target, parent);
		} catch (IOException e) {
			throw new SnapshotExportException("Cannot validate combined snapshot publish target", e);
		}
	}

	private static void deleteDirectoryQuietly(Path directory) {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Preserve the publication failure. Staging names are private and never served.
		}
	}

	public record PublicationResult(
			Path publicationDirectory,
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest archiveManifest,
			VerifiedCoreSnapshotArchive verifiedArchive,
			Hash stateManifestSigningHash,
			Hash archiveManifestSigningHash,
			int stateChunkCount,
			int archiveChunkCount,
			int entityChunkCount,
			ExplorerSnapshotManifest explorerManifest,
			int explorerChunkCount) {
		public PublicationResult {
			Objects.requireNonNull(publicationDirectory, "publicationDirectory");
			Objects.requireNonNull(stateManifest, "stateManifest");
			Objects.requireNonNull(archiveManifest, "archiveManifest");
			Objects.requireNonNull(verifiedArchive, "verifiedArchive");
			Objects.requireNonNull(stateManifestSigningHash, "stateManifestSigningHash");
			Objects.requireNonNull(archiveManifestSigningHash, "archiveManifestSigningHash");
			if (explorerChunkCount < 0
					|| explorerManifest == null && explorerChunkCount != 0
					|| explorerManifest != null && explorerChunkCount != explorerManifest.chunks().size()) {
				throw new IllegalArgumentException("Explorer publication result does not match its manifest");
			}
		}
	}

	private record PublishTarget(Path path, Path parent) {
	}
}
