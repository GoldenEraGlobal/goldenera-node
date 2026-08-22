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
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

/** Crash-safe filesystem state for the single automatic snapshot publisher. */
public final class SnapshotPublicationStore {

	private static final String CONTROL_DIRECTORY = ".publisher";
	private static final String STATE_FILE = "state.properties";
	private static final String LOCK_FILE = "publisher.lock";
	private static final int RETAINED_VERSIONS = 2;

	private final Path root;
	private final Path versions;
	private final Path control;
	private final SnapshotPublicationDirectorySelector selector = new SnapshotPublicationDirectorySelector();

	public SnapshotPublicationStore(Path root) throws IOException {
		this.root = root.toAbsolutePath().normalize();
		Files.createDirectories(this.root);
		if (Files.isSymbolicLink(this.root) || !this.root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(this.root)) {
			throw new IOException("Snapshot publication root must be a real directory");
		}
		versions = Files.createDirectories(this.root.resolve(SnapshotPublicationDirectorySelector.VERSIONS_DIRECTORY));
		control = Files.createDirectories(this.root.resolve(CONTROL_DIRECTORY));
	}

	public <T> T withLock(IoSupplier<T> operation) throws Exception {
		try (FileChannel channel = FileChannel.open(control.resolve(LOCK_FILE), CREATE, WRITE,
				LinkOption.NOFOLLOW_LINKS); FileLock lock = channel.tryLock()) {
			if (lock == null) {
				throw new LockUnavailableException();
			}
			return operation.get();
		} catch (OverlappingFileLockException e) {
			throw new LockUnavailableException();
		}
	}

	public Path createGenerationDirectory() throws IOException {
		return Files.createTempDirectory(control, "generation-").toRealPath();
	}

	public void cleanupStaleGenerations() throws IOException {
		try (var entries = Files.list(control)) {
			for (Path entry : entries.filter(path -> path.getFileName().toString().startsWith("generation-")).toList()) {
				cleanup(entry);
			}
		}
	}

	public Optional<PublishedSnapshot> current() {
		return selector.resolve(root).flatMap(directory -> parseVersion(directory.getFileName().toString(), directory));
	}

	public void withdrawCurrent() throws IOException {
		Files.deleteIfExists(root.resolve(SnapshotPublicationDirectorySelector.CURRENT_FILE));
		forceDirectory(root);
	}

	public PublisherState loadState() {
		Path file = control.resolve(STATE_FILE);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
			return PublisherState.initial();
		}
		try (var input = Files.newInputStream(file, READ, LinkOption.NOFOLLOW_LINKS)) {
			Properties values = new Properties();
			values.load(input);
			if (!"1".equals(values.getProperty("format"))) {
				return PublisherState.initial();
			}
			return new PublisherState(
					Long.parseLong(values.getProperty("lastPublishedHeight", "-1")),
					Long.parseLong(values.getProperty("lastPublishedAtMillis", "0")),
					Integer.parseInt(values.getProperty("failures", "0")),
					Long.parseLong(values.getProperty("nextRetryAtMillis", "0")));
		} catch (Exception e) {
			return PublisherState.initial();
		}
	}

	public void saveState(PublisherState state) throws IOException {
		String body = "format=1\nlastPublishedHeight=" + state.lastPublishedHeight()
				+ "\nlastPublishedAtMillis=" + state.lastPublishedAtMillis()
				+ "\nfailures=" + state.failures()
				+ "\nnextRetryAtMillis=" + state.nextRetryAtMillis() + "\n";
		Path temporary = control.resolve(STATE_FILE + ".tmp-" + UUID.randomUUID());
		writeForced(temporary, body);
		Files.move(temporary, control.resolve(STATE_FILE), ATOMIC_MOVE, REPLACE_EXISTING);
		forceDirectory(control);
	}

	public PublishedSnapshot publish(Path ready, long height, Hash hash, Instant now) throws IOException {
		String versionName = SnapshotFormatCompatibility.currentVersionName(height, hash);
		Path version = versions.resolve(versionName);
		if (Files.notExists(version, LinkOption.NOFOLLOW_LINKS)) {
			Files.move(ready, version, ATOMIC_MOVE);
			forceDirectory(versions);
		} else if (Files.isSymbolicLink(version)
				|| !Files.isRegularFile(version.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(version.resolve("archive-manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Existing immutable snapshot version is incomplete or unsafe");
		}
		Path pointerPart = root.resolve(".current.tmp-" + UUID.randomUUID());
		writeForced(pointerPart, versionName + "\n");
		Files.move(pointerPart, root.resolve(SnapshotPublicationDirectorySelector.CURRENT_FILE),
				ATOMIC_MOVE, REPLACE_EXISTING);
		forceDirectory(root);
		pruneVersions(versionName);
		return new PublishedSnapshot(height, hash, version, now);
	}

	private void pruneVersions(String currentVersion) throws IOException {
		List<Path> candidates;
		try (var entries = Files.list(versions)) {
			candidates = entries
					.filter(path -> path.getFileName().toString()
							.matches(SnapshotFormatCompatibility.VERSION_NAME_PATTERN))
					.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
							&& !Files.isSymbolicLink(path))
					.sorted(Comparator.comparingLong(this::lastModified).reversed())
					.toList();
		}
		Set<Path> retained = new HashSet<>();
		Path current = versions.resolve(currentVersion);
		retained.add(current);
		for (Path candidate : candidates) {
			if (retained.size() >= RETAINED_VERSIONS) {
				break;
			}
			retained.add(candidate);
		}
		for (Path candidate : candidates) {
			if (!retained.contains(candidate)) {
				cleanup(candidate);
			}
		}
	}

	private long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
		} catch (IOException e) {
			return Long.MIN_VALUE;
		}
	}

	public void cleanup(Path directory) {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Private staging is never served; a later maintenance pass may remove it.
		}
	}

	private Optional<PublishedSnapshot> parseVersion(String name, Path directory) {
		try {
			String[] parts = name.split("-", 3);
			String hash = parts[2].substring(0, 64);
			return Optional.of(new PublishedSnapshot(
					Long.parseLong(parts[1]), Hash.fromHexString("0x" + hash), directory,
					Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant()));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private void writeForced(Path path, String value) throws IOException {
		try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			while (bytes.hasRemaining()) {
				channel.write(bytes);
			}
			channel.force(true);
		}
	}

	private void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, READ)) {
			channel.force(true);
		}
	}

	public record PublishedSnapshot(long height, Hash hash, Path directory, Instant publishedAt) {
	}

	public record PublisherState(
			long lastPublishedHeight, long lastPublishedAtMillis, int failures, long nextRetryAtMillis) {
		public static PublisherState initial() {
			return new PublisherState(-1, 0, 0, 0);
		}
	}

	@FunctionalInterface
	public interface IoSupplier<T> {
		T get() throws Exception;
	}

	public static final class LockUnavailableException extends IOException {
		private static final long serialVersionUID = 1L;

		private LockUnavailableException() {
			super("Another snapshot publisher owns the persistent lock");
		}
	}
}
