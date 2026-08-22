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

import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

/** Resolves the atomically selected immutable publication directory without following symlinks. */
public final class SnapshotPublicationDirectorySelector {

	public static final String CURRENT_FILE = "current";
	public static final String VERSIONS_DIRECTORY = "versions";
	private static final int MAX_POINTER_BYTES = 160;

	public Optional<Path> resolve(Path configuredRoot) {
		if (configuredRoot == null) {
			return Optional.empty();
		}
		try {
			Path root = configuredRoot.toAbsolutePath().normalize();
			if (hasSymlinkComponent(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
					|| !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) {
				return Optional.empty();
			}
			Path pointer = root.resolve(CURRENT_FILE);
			if (Files.notExists(pointer, LinkOption.NOFOLLOW_LINKS)) {
				return hasCoreManifests(root) ? Optional.of(root) : Optional.empty();
			}
			String version = readPointer(pointer);
			if (!version.matches(SnapshotFormatCompatibility.VERSION_NAME_PATTERN)) {
				return Optional.empty();
			}
			return resolveVersion(root, version);
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	public Optional<Path> resolveVersion(Path configuredRoot, String version) {
		if (configuredRoot == null || version == null
				|| !version.matches(SnapshotFormatCompatibility.VERSION_NAME_PATTERN)) {
			return Optional.empty();
		}
		try {
			Path root = configuredRoot.toAbsolutePath().normalize();
			if (hasSymlinkComponent(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
					|| !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) {
				return Optional.empty();
			}
			Path versions = root.resolve(VERSIONS_DIRECTORY);
			Path selected = versions.resolve(version).normalize();
			if (!selected.getParent().equals(versions) || Files.isSymbolicLink(versions)
					|| Files.isSymbolicLink(selected) || !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
				return Optional.empty();
			}
			Path real = selected.toRealPath(LinkOption.NOFOLLOW_LINKS);
			return real.getParent().equals(versions.toRealPath(LinkOption.NOFOLLOW_LINKS)) && hasCoreManifests(real)
					? Optional.of(real) : Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	private String readPointer(Path pointer) throws IOException {
		if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pointer)) {
			throw new IOException("Snapshot current pointer is unsafe");
		}
		try (FileChannel channel = FileChannel.open(pointer, READ, LinkOption.NOFOLLOW_LINKS)) {
			long size = channel.size();
			if (size <= 0 || size > MAX_POINTER_BYTES) {
				throw new IOException("Snapshot current pointer has invalid size");
			}
			ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(size));
			while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
				// Continue until the fixed-size pointer is complete.
			}
			return new String(bytes.array(), StandardCharsets.US_ASCII).trim();
		}
	}

	private boolean hasCoreManifests(Path directory) {
		return regular(directory.resolve("manifest.json"))
				&& regular(directory.resolve("archive-manifest.json"));
	}

	private boolean regular(Path path) {
		return !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
	}

	private boolean hasSymlinkComponent(Path absolute) {
		Path current = absolute.getRoot();
		for (Path component : absolute) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				return true;
			}
		}
		return false;
	}
}
