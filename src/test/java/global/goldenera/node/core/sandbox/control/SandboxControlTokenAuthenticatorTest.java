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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.bucket4j.Bucket;
import jakarta.servlet.ServletException;

class SandboxControlTokenAuthenticatorTest {

	private static final String TOKEN = token((byte) 7);

	@TempDir
	Path temporaryDirectory;

	@Test
	void readsValidTokenOnceAndUsesConstantDigestComparison() throws Exception {
		Path path = tokenFile("control.token", TOKEN, PosixFilePermission.OWNER_READ);
		SandboxControlTokenAuthenticator authenticator = SandboxControlTokenAuthenticator.load(path);

		assertThat(authenticator.authenticate(TOKEN)).isTrue();
		assertThat(authenticator.authenticate(token((byte) 8))).isFalse();

		Files.delete(path);
		path = tokenFile("control.token", token((byte) 9), PosixFilePermission.OWNER_READ);
		assertThat(authenticator.authenticate(TOKEN)).isTrue();
		assertThat(authenticator.authenticate(token((byte) 9))).isFalse();

		authenticator.destroy();
		assertThat(authenticator.authenticate(TOKEN)).isFalse();
	}

	@Test
	void acceptsOnlyOwnerReadOrOwnerReadWritePermissions() throws Exception {
		Path readOnly = tokenFile("read-only.token", TOKEN, PosixFilePermission.OWNER_READ);
		Path readWrite = tokenFile(
				"read-write.token", TOKEN, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

		assertThat(SandboxControlTokenAuthenticator.load(readOnly).authenticate(TOKEN)).isTrue();
		assertThat(SandboxControlTokenAuthenticator.load(readWrite).authenticate(TOKEN)).isTrue();

		Path broad = tokenFile(
				"broad.token", TOKEN, PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ);
		assertInvalid(broad);
	}

	@Test
	void rejectsRelativeDirectorySymlinkOversizeAndNonCanonicalFormatsWithoutDisclosure() throws Exception {
		assertInvalid(Path.of("relative.token"));
		assertInvalid(temporaryDirectory);

		Path target = tokenFile("target.token", TOKEN, PosixFilePermission.OWNER_READ);
		Path link = temporaryDirectory.resolve("link.token");
		Files.createSymbolicLink(link, target);
		assertInvalid(link);

		assertInvalid(tokenFile("newline.token", TOKEN + "\n", PosixFilePermission.OWNER_READ));
		assertInvalid(tokenFile("padding.token", TOKEN + "=", PosixFilePermission.OWNER_READ));
		assertInvalid(tokenFile("short.token", TOKEN.substring(1), PosixFilePermission.OWNER_READ));
		assertInvalid(tokenFile("oversize.token", "x".repeat(129), PosixFilePermission.OWNER_READ));
	}

	@Test
	void rejectsPathReplacementBetweenOpeningAndAttributeRecheck() throws Exception {
		Path path = tokenFile("replaced.token", TOKEN, PosixFilePermission.OWNER_READ);

		assertThatThrownBy(() -> SandboxControlTokenAuthenticator.load(path, () -> {
			try {
				Files.delete(path);
				Files.writeString(path, TOKEN, StandardCharsets.US_ASCII);
				Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}))
				.isInstanceOf(SandboxControlActivationException.class)
				.hasMessage("Sandbox control API token file is invalid or cannot be read");
	}

	@Test
	void rejectsWritableParentBeforeCoordinatedSwapAndRestoreCanRun() throws Exception {
		Path writableParent = temporaryDirectory.resolve("writable-parent");
		Files.createDirectory(writableParent);
		Files.setPosixFilePermissions(writableParent, EnumSet.of(
				PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE,
				PosixFilePermission.GROUP_WRITE));
		Path path = writableParent.resolve("control.token");
		Files.writeString(path, TOKEN, StandardCharsets.US_ASCII);
		Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ));
		Path original = writableParent.resolve("original.token");
		Path replacement = writableParent.resolve("replacement.token");
		Files.writeString(replacement, token((byte) 12), StandardCharsets.US_ASCII);
		Files.setPosixFilePermissions(replacement, EnumSet.of(PosixFilePermission.OWNER_READ));
		AtomicBoolean swapAttempted = new AtomicBoolean();

		try {
			assertThatThrownBy(() -> SandboxControlTokenAuthenticator.load(path, () -> {
				swapAttempted.set(true);
				try {
					Files.move(path, original, StandardCopyOption.ATOMIC_MOVE);
					Files.move(replacement, path, StandardCopyOption.ATOMIC_MOVE);
					Files.delete(path);
					Files.move(original, path, StandardCopyOption.ATOMIC_MOVE);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}))
					.isInstanceOf(SandboxControlActivationException.class)
					.hasMessage("Sandbox control API token file is invalid or cannot be read");
			assertThat(swapAttempted).isFalse();
		} finally {
			Files.setPosixFilePermissions(writableParent, EnumSet.of(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE));
		}
	}

	@Test
	void securityFilterRejectsAConcurrentlyExcessRequestWithoutWaiting() throws Exception {
		Path path = tokenFile(
				"concurrency.token", TOKEN,
				PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
		SandboxControlTokenAuthenticator authenticator = SandboxControlTokenAuthenticator.load(path);
		Bucket bucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(100).refillGreedy(100, Duration.ofSeconds(1)))
				.build();
		Bucket authenticationFailureBucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(100).refillGreedy(100, Duration.ofSeconds(1)))
				.build();
		SandboxControlSecurityFilter filter = new SandboxControlSecurityFilter(
				authenticator,
				new SandboxControlAuditLog(),
				bucket,
				authenticationFailureBucket,
				new ObjectMapper());
		CountDownLatch entered = new CountDownLatch(SandboxControlSecurityFilter.MAX_CONCURRENT_REQUESTS);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(SandboxControlSecurityFilter.MAX_CONCURRENT_REQUESTS);
		try {
			List<? extends Future<?>> requests = IntStream.range(
					0, SandboxControlSecurityFilter.MAX_CONCURRENT_REQUESTS)
					.mapToObj(index -> executor.submit(() -> {
						filter.doFilter(request(), new MockHttpServletResponse(), (incoming, outgoing) -> {
								entered.countDown();
								try {
									release.await();
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									throw new ServletException("interrupted", e);
								}
							});
						return null;
					}))
					.toList();
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

			MockHttpServletResponse rejected = new MockHttpServletResponse();
			filter.doFilter(request(), rejected, (incoming, outgoing) -> {
				throw new AssertionError("concurrently excess request reached the application");
			});

			assertThat(rejected.getStatus()).isEqualTo(429);
			assertThat(rejected.getContentAsString()).contains("CONCURRENCY_LIMITED");
			release.countDown();
			for (Future<?> request : requests) {
				request.get(2, TimeUnit.SECONDS);
			}
		} finally {
			release.countDown();
			executor.shutdownNow();
			authenticator.destroy();
		}
	}

	private MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sandbox/v1/control/state");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
		return request;
	}

	private Path tokenFile(String name, String content, PosixFilePermission... permissions) throws Exception {
		Path path = temporaryDirectory.resolve(name);
		Files.writeString(path, content, StandardCharsets.US_ASCII);
		Files.setPosixFilePermissions(path, EnumSet.copyOf(List.of(permissions)));
		return path;
	}

	private void assertInvalid(Path path) {
		assertThatThrownBy(() -> SandboxControlTokenAuthenticator.load(path))
				.isInstanceOf(SandboxControlActivationException.class)
				.hasMessage("Sandbox control API token file is invalid or cannot be read")
				.hasMessageNotContaining(path.toString())
				.hasMessageNotContaining(TOKEN);
	}

	private static String token(byte value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
