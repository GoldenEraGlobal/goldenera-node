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
package global.goldenera.node.core.sandbox.control;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.PreDestroy;

final class SandboxControlTokenAuthenticator {

	static final int MAX_TOKEN_FILE_BYTES = 128;
	private static final Set<PosixFilePermission> MODE_0400 =
			Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ));
	private static final Set<PosixFilePermission> MODE_0600 =
			Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

	private final byte[] expectedDigest;

	private SandboxControlTokenAuthenticator(byte[] expectedDigest) {
		this.expectedDigest = expectedDigest;
	}

	static SandboxControlTokenAuthenticator load(Path path) {
		return load(path, () -> { });
	}

	static SandboxControlTokenAuthenticator load(Path path, Runnable afterOpen) {
		if (path == null || !path.isAbsolute()) {
			throw invalidTokenFile();
		}
		Path normalizedPath = path.normalize();
		if (!normalizedPath.equals(path)) {
			throw invalidTokenFile();
		}
		Objects.requireNonNull(afterOpen, "afterOpen");
		try {
			Path configuredParent = normalizedPath.getParent();
			validateParentChain(configuredParent, true);
			Path trustedParent = configuredParent.toRealPath();
			validateParentChain(trustedParent, false);
			Path trustedPath = trustedParent.resolve(normalizedPath.getFileName());
			PosixFileAttributes before = Files.readAttributes(
					trustedPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			validateAttributes(before);
			try (SeekableByteChannel channel = Files.newByteChannel(
					trustedPath,
					StandardOpenOption.READ,
					LinkOption.NOFOLLOW_LINKS)) {
				afterOpen.run();
				byte[] token = readBounded(channel);
				PosixFileAttributes after = Files.readAttributes(
						trustedPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				validateAttributes(after);
				if (!sameFile(before, after)) {
					throw invalidTokenFile();
				}
				try {
					validateToken(token);
					return new SandboxControlTokenAuthenticator(digest(token));
				} finally {
					Arrays.fill(token, (byte) 0);
				}
			}
		} catch (IOException | UnsupportedOperationException e) {
			throw invalidTokenFile();
		}
	}

	private static void validateParentChain(Path immediateParent, boolean allowRootOwnedSystemSymlink)
			throws IOException {
		if (immediateParent == null) {
			throw invalidTokenFile();
		}
		String processOwner = System.getProperty("user.name");
		if (processOwner == null || processOwner.isBlank()) {
			throw invalidTokenFile();
		}
		boolean immediate = true;
		for (Path directory = immediateParent; directory != null; directory = directory.getParent()) {
			PosixFileAttributes attributes = Files.readAttributes(
					directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			String owner = attributes.owner().getName();
			if (attributes.isSymbolicLink()) {
				if (immediate || !allowRootOwnedSystemSymlink || !"root".equals(owner)) {
					throw invalidTokenFile();
				}
				immediate = false;
				continue;
			}
			if (!attributes.isDirectory() || attributes.fileKey() == null
					|| !owner.equals(processOwner) && !owner.equals("root")) {
				throw invalidTokenFile();
			}
			Set<PosixFilePermission> permissions = attributes.permissions();
			boolean broadlyWritable = permissions.contains(PosixFilePermission.GROUP_WRITE)
					|| permissions.contains(PosixFilePermission.OTHERS_WRITE);
			if (immediate && (!owner.equals(processOwner) || broadlyWritable)) {
				throw invalidTokenFile();
			}
			if (!immediate && broadlyWritable && !isTrustedStickySystemDirectory(directory, owner)) {
				throw invalidTokenFile();
			}
			immediate = false;
		}
	}

	private static boolean isTrustedStickySystemDirectory(Path directory, String owner) throws IOException {
		if (!"root".equals(owner)) {
			return false;
		}
		Object mode = Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
		return mode instanceof Integer numericMode && (numericMode & 01000) != 0;
	}

	boolean authenticate(String candidate) {
		byte[] candidateBytes = candidate == null
				? new byte[0]
				: candidate.getBytes(StandardCharsets.US_ASCII);
		try {
			return MessageDigest.isEqual(expectedDigest, digest(candidateBytes));
		} finally {
			Arrays.fill(candidateBytes, (byte) 0);
		}
	}

	@PreDestroy
	void destroy() {
		Arrays.fill(expectedDigest, (byte) 0);
	}

	private static byte[] readBounded(SeekableByteChannel channel) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(MAX_TOKEN_FILE_BYTES + 1);
		while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
			// Continue until EOF or the hard limit is exceeded.
		}
		if (buffer.position() > MAX_TOKEN_FILE_BYTES) {
			throw invalidTokenFile();
		}
		return Arrays.copyOf(buffer.array(), buffer.position());
	}

	private static void validateAttributes(PosixFileAttributes attributes) {
		if (!attributes.isRegularFile() || attributes.fileKey() == null
				|| attributes.size() > MAX_TOKEN_FILE_BYTES) {
			throw invalidTokenFile();
		}
		String processOwner = System.getProperty("user.name");
		if (processOwner == null || !attributes.owner().getName().equals(processOwner)) {
			throw invalidTokenFile();
		}
		Set<PosixFilePermission> permissions = attributes.permissions();
		if (!permissions.equals(MODE_0400) && !permissions.equals(MODE_0600)) {
			throw invalidTokenFile();
		}
	}

	private static boolean sameFile(PosixFileAttributes before, PosixFileAttributes after) {
		return Objects.equals(before.fileKey(), after.fileKey())
				&& Objects.equals(before.owner(), after.owner())
				&& before.permissions().equals(after.permissions())
				&& before.size() == after.size()
				&& before.lastModifiedTime().equals(after.lastModifiedTime());
	}

	private static void validateToken(byte[] token) {
		if (token.length != 43) {
			throw invalidTokenFile();
		}
		for (byte value : token) {
			if (!isBase64Url(value)) {
				throw invalidTokenFile();
			}
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(token);
			try {
				byte[] canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded);
				try {
					if (decoded.length != 32
							|| !MessageDigest.isEqual(canonical, token)) {
						throw invalidTokenFile();
					}
				} finally {
					Arrays.fill(canonical, (byte) 0);
				}
			} finally {
				Arrays.fill(decoded, (byte) 0);
			}
		} catch (IllegalArgumentException e) {
			throw invalidTokenFile();
		}
	}

	private static boolean isBase64Url(byte value) {
		return value >= 'A' && value <= 'Z'
				|| value >= 'a' && value <= 'z'
				|| value >= '0' && value <= '9'
				|| value == '_'
				|| value == '-';
	}

	private static byte[] digest(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static SandboxControlActivationException invalidTokenFile() {
		return new SandboxControlActivationException(
				"Sandbox control API token file is invalid or cannot be read");
	}
}
