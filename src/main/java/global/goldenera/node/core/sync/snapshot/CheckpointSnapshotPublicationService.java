/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
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
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.BinarySnapshotNodeSource;

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

			List<Path> stateChunks = moveStateChunks(state, readyDirectory);
			List<Path> archiveChunks = moveArchiveChunks(archive, readyDirectory);
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
							archiveChunks.get(descriptor.index()), LinkOption.NOFOLLOW_LINKS));

			PublicationResult result = new PublicationResult(
					target.path(), state.manifest(), archive.manifest(), verified,
					state.manifestSigningHash(), archive.manifestSigningHash(),
					stateChunks.size(), archiveChunks.size());
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
			int archiveChunkCount) {
		public PublicationResult {
			Objects.requireNonNull(publicationDirectory, "publicationDirectory");
			Objects.requireNonNull(stateManifest, "stateManifest");
			Objects.requireNonNull(archiveManifest, "archiveManifest");
			Objects.requireNonNull(verifiedArchive, "verifiedArchive");
			Objects.requireNonNull(stateManifestSigningHash, "stateManifestSigningHash");
			Objects.requireNonNull(archiveManifestSigningHash, "archiveManifestSigningHash");
		}
	}

	private record PublishTarget(Path path, Path parent) {
	}
}
