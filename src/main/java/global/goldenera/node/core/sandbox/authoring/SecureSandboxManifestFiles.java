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
package global.goldenera.node.core.sandbox.authoring;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;

/** Secure sandbox manifest reads and atomic no-overwrite publication. */
final class SecureSandboxManifestFiles {

	private static final Set<OpenOption> READ_NOFOLLOW = Set.of(READ, NOFOLLOW_LINKS);
	private static final Set<OpenOption> CREATE_WRITE_NOFOLLOW =
			Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);

	Path requireStrictPath(Path path, String description) {
		if (path == null || !path.isAbsolute()) {
			throw new SandboxManifestAuthoringException(description + " path must be absolute");
		}
		Path normalized = path.normalize();
		if (!path.equals(normalized)) {
			throw new SandboxManifestAuthoringException(description + " path must already be normalized");
		}
		if (normalized.getFileName() == null) {
			throw new SandboxManifestAuthoringException(description + " path must name a file");
		}
		return normalized;
	}

	byte[] readDraft(Path draft) {
		SecureDirectoryStream<Path> openedParent;
		try {
			openedParent = openSecureParent(draft, "Draft manifest");
		} catch (IOException e) {
			throw new SandboxManifestAuthoringException("Cannot securely read draft manifest", e);
		}
		if (openedParent == null) {
			return readDraftPortable(draft);
		}
		try (SecureDirectoryStream<Path> parent = openedParent) {
			Path name = draft.getFileName();
			BasicFileAttributeView view = parent.getFileAttributeView(
					name, BasicFileAttributeView.class, NOFOLLOW_LINKS);
			if (view == null) {
				throw new SandboxManifestAuthoringException(
						"Filesystem cannot securely inspect the draft manifest");
			}
			BasicFileAttributes attributes = view.readAttributes();
			if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
				throw new SandboxManifestAuthoringException(
						"Draft manifest path must be a regular file without symlinks");
			}
			Object expectedKey = attributes.fileKey();
			if (expectedKey == null) {
				throw new SandboxManifestAuthoringException(
						"Filesystem cannot bind draft identity during secure read");
			}
			try (SeekableByteChannel channel = parent.newByteChannel(name, READ_NOFOLLOW)) {
				byte[] bytes = readBounded(channel);
				if (!expectedKey.equals(fileKey(parent, name))) {
					throw new SandboxManifestAuthoringException("Draft manifest changed during secure read");
				}
				return bytes;
			}
		} catch (SandboxManifestAuthoringException e) {
			throw e;
		} catch (IOException e) {
			throw new SandboxManifestAuthoringException("Cannot securely read draft manifest", e);
		}
	}

	void publish(Path output, byte[] bytes) {
		SecureDirectoryStream<Path> openedParent;
		try {
			openedParent = openSecureParent(output, "Output manifest");
		} catch (IOException e) {
			throw new SandboxManifestAuthoringException(
					"Failed descriptor-bound atomic manifest publication", e);
		}
		if (openedParent == null) {
			publishPortable(output, bytes);
			return;
		}
		try (SecureDirectoryStream<Path> parent = openedParent) {
			publishInDirectory(parent, output.getFileName(), bytes);
		} catch (FileAlreadyExistsException e) {
			throw new SandboxManifestAuthoringException(
					"Output manifest already exists; refusing to overwrite it", e);
		} catch (SandboxManifestAuthoringException e) {
			throw e;
		} catch (IOException | UnsupportedOperationException e) {
			throw new SandboxManifestAuthoringException(
					"Failed descriptor-bound atomic manifest publication", e);
		}
	}

	private void publishInDirectory(
			SecureDirectoryStream<Path> parent,
			Path outputName,
			byte[] bytes) throws IOException {
		Path temporaryName = Path.of(".sandbox-manifest-" + UUID.randomUUID() + ".tmp");
		boolean temporaryExists = false;
		boolean reservationExists = false;
		Object temporaryKey = null;
		Object reservationKey = null;
		try {
			try (SeekableByteChannel channel = parent.newByteChannel(
					temporaryName,
					CREATE_WRITE_NOFOLLOW,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
				temporaryExists = true;
				writeFully(channel, bytes);
				if (channel instanceof FileChannel fileChannel) {
					fileChannel.force(true);
				}
			}
			temporaryKey = fileKey(parent, temporaryName);
			try (SeekableByteChannel ignored = parent.newByteChannel(
					outputName,
					CREATE_WRITE_NOFOLLOW,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")))) {
				reservationExists = true;
			}
			reservationKey = fileKey(parent, outputName);
			parent.move(temporaryName, parent, outputName);
			temporaryExists = false;
			reservationExists = false;
		} finally {
			cleanup(
					parent, outputName, temporaryName, temporaryExists, temporaryKey,
					reservationExists, reservationKey);
		}
	}

	private SecureDirectoryStream<Path> openSecureParent(Path file, String description) throws IOException {
		Path parent = file.getParent();
		if (parent == null) {
			throw new SandboxManifestAuthoringException(description + " parent is missing");
		}
		DirectoryStream<Path> rootStream = Files.newDirectoryStream(parent.getRoot());
		if (!(rootStream instanceof SecureDirectoryStream<?>)) {
			rootStream.close();
			return null;
		}
		@SuppressWarnings("unchecked")
		SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) rootStream;
		try {
			for (Path component : parent) {
				SecureDirectoryStream<Path> previous = current;
				current = previous.newDirectoryStream(component, NOFOLLOW_LINKS);
				previous.close();
			}
			return current;
		} catch (IOException | RuntimeException e) {
			current.close();
			throw e;
		}
	}

	private byte[] readDraftPortable(Path draft) {
		try {
			DirectoryIdentity parentBefore = inspectDirectory(draft.getParent());
			BasicFileAttributes before = inspectRegularFile(draft, "Draft manifest");
			try (SeekableByteChannel channel = Files.newByteChannel(draft, READ_NOFOLLOW)) {
				byte[] bytes = readBounded(channel);
				BasicFileAttributes after = inspectRegularFile(draft, "Draft manifest");
				DirectoryIdentity parentAfter = inspectDirectory(draft.getParent());
				if (!sameKey(before.fileKey(), after.fileKey()) || !parentBefore.equals(parentAfter)) {
					throw new SandboxManifestAuthoringException("Draft manifest path changed during secure read");
				}
				return bytes;
			}
		} catch (SandboxManifestAuthoringException e) {
			throw e;
		} catch (IOException e) {
			throw new SandboxManifestAuthoringException("Cannot securely read draft manifest", e);
		}
	}

	private void publishPortable(Path output, byte[] bytes) {
		Path parent = output.getParent();
		Path temporary = parent.resolve(".sandbox-manifest-" + UUID.randomUUID() + ".tmp");
		boolean temporaryExists = false;
		boolean reservationExists = false;
		Object temporaryKey = null;
		Object reservationKey = null;
		DirectoryIdentity identity = null;
		try {
			identity = inspectDirectory(parent);
			try (SeekableByteChannel channel = Files.newByteChannel(
					temporary,
					CREATE_WRITE_NOFOLLOW,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
				temporaryExists = true;
				writeFully(channel, bytes);
				if (channel instanceof FileChannel fileChannel) {
					fileChannel.force(true);
				}
			}
			temporaryKey = inspectRegularFile(temporary, "Temporary manifest").fileKey();
			try (SeekableByteChannel ignored = Files.newByteChannel(
					output,
					CREATE_WRITE_NOFOLLOW,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")))) {
				reservationExists = true;
			}
			reservationKey = inspectRegularFile(output, "Output reservation").fileKey();
			if (!identity.equals(inspectDirectory(parent))) {
				throw new SandboxManifestAuthoringException("Output parent changed during publication");
			}
			Files.move(temporary, output, ATOMIC_MOVE);
			temporaryExists = false;
			reservationExists = false;
			if (!identity.equals(inspectDirectory(parent))) {
				throw new SandboxManifestAuthoringException("Output parent changed during publication");
			}
		} catch (FileAlreadyExistsException e) {
			throw new SandboxManifestAuthoringException(
					"Output manifest already exists; refusing to overwrite it", e);
		} catch (SandboxManifestAuthoringException e) {
			throw e;
		} catch (IOException | UnsupportedOperationException e) {
			throw new SandboxManifestAuthoringException("Failed portable atomic manifest publication", e);
		} finally {
			cleanupPortable(
					identity, temporary, temporaryExists, temporaryKey,
					output, reservationExists, reservationKey);
		}
	}

	private DirectoryIdentity inspectDirectory(Path directory) throws IOException {
		List<Object> keys = new ArrayList<>();
		Path current = directory.getRoot();
		keys.add(directoryKey(current));
		for (Path component : directory) {
			current = current.resolve(component);
			keys.add(directoryKey(current));
		}
		return new DirectoryIdentity(List.copyOf(keys));
	}

	private Object directoryKey(Path directory) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(
				directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
		if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
			throw new SandboxManifestAuthoringException(
					"Manifest path contains a symlink or an unidentifiable directory: " + directory);
		}
		return attributes.fileKey();
	}

	private BasicFileAttributes inspectRegularFile(Path file, String description) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
			throw new SandboxManifestAuthoringException(
					description + " path must be an identifiable regular file without symlinks");
		}
		return attributes;
	}

	private boolean sameKey(Object first, Object second) {
		return first != null && first.equals(second);
	}

	private void cleanupPortable(
			DirectoryIdentity identity,
			Path temporary,
			boolean temporaryExists,
			Object temporaryKey,
			Path output,
			boolean reservationExists,
			Object reservationKey) {
		if (identity == null) {
			return;
		}
		try {
			if (!identity.equals(inspectDirectory(output.getParent()))) {
				return;
			}
			cleanupPortableFile(temporary, temporaryExists, temporaryKey);
			cleanupPortableFile(output, reservationExists, reservationKey);
		} catch (IOException | RuntimeException e) {
			// Best effort only. A zero-permission reservation fails closed.
		}
	}

	private void cleanupPortableFile(Path file, boolean exists, Object expectedKey) throws IOException {
		if (exists && sameKey(expectedKey, inspectRegularFile(file, "Authoring temporary").fileKey())) {
			Files.deleteIfExists(file);
		}
	}

	private byte[] readBounded(SeekableByteChannel channel) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		int total = 0;
		while (channel.read(buffer) >= 0) {
			buffer.flip();
			int count = buffer.remaining();
			total += count;
			if (total > SandboxManifestLoader.MAX_MANIFEST_BYTES) {
				throw new SandboxManifestAuthoringException("Sandbox manifest exceeds the 1 MiB limit");
			}
			output.write(buffer.array(), buffer.position(), count);
			buffer.clear();
		}
		return output.toByteArray();
	}

	private void writeFully(SeekableByteChannel channel, byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private Object fileKey(SecureDirectoryStream<Path> parent, Path name) throws IOException {
		BasicFileAttributeView view = parent.getFileAttributeView(
				name, BasicFileAttributeView.class, NOFOLLOW_LINKS);
		return view == null ? null : view.readAttributes().fileKey();
	}

	private void cleanup(
			SecureDirectoryStream<Path> parent,
			Path output,
			Path temporary,
			boolean temporaryExists,
			Object temporaryKey,
			boolean reservationExists,
			Object reservationKey) {
		cleanupFile(parent, temporary, temporaryExists, temporaryKey);
		cleanupFile(parent, output, reservationExists, reservationKey);
	}

	private void cleanupFile(
			SecureDirectoryStream<Path> parent,
			Path name,
			boolean exists,
			Object expectedKey) {
		try {
			if (exists && sameFile(parent, name, expectedKey)) {
				parent.deleteFile(name);
			}
		} catch (IOException | RuntimeException e) {
			// Best effort only. A zero-permission reservation fails closed.
		}
	}

	private boolean sameFile(SecureDirectoryStream<Path> parent, Path name, Object expectedKey) throws IOException {
		return expectedKey != null && expectedKey.equals(fileKey(parent, name));
	}

	private record DirectoryIdentity(List<Object> componentKeys) {
	}
}
