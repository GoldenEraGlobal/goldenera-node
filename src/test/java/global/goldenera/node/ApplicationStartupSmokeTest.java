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
package global.goldenera.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApplicationStartupSmokeTest {

	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
	private static final String STARTED_MARKER = "Started Application";
	private static final String CORE_READY_MARKER = "CORE: Core initialization successful";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	@TempDir
	Path temporaryDirectory;

	@Test
	@Timeout(120)
	void productionClasspathReachesSpringAndCoreReady() throws Exception {
		ProcessBuilder builder = new ProcessBuilder(
				javaExecutable(),
				"-Xms256m",
				"-Xmx1024m",
				"-Dspring.main.banner-mode=off",
				"-cp",
				productionClasspath(),
				Application.class.getName());
		builder.redirectErrorStream(true);
		configureEnvironment(builder.environment());

		Process process = builder.start();
		StringBuilder output = new StringBuilder();
		AtomicBoolean springStarted = new AtomicBoolean();
		AtomicBoolean coreReady = new AtomicBoolean();
		CountDownLatch ready = new CountDownLatch(1);
		Thread reader = Thread.ofVirtual().start(() -> readOutput(
				process, output, springStarted, coreReady, ready));

		try {
			boolean reachedReady = awaitReadyOrExit(process, ready, STARTUP_TIMEOUT);
			assertThat(reachedReady)
					.withFailMessage("Application did not become ready. Output tail:%n%s", outputTail(output))
					.isTrue();
			assertThat(springStarted)
					.withFailMessage("Spring startup marker is missing. Output tail:%n%s", outputTail(output))
					.isTrue();
			assertThat(coreReady)
					.withFailMessage("Core readiness marker is missing. Output tail:%n%s", outputTail(output))
					.isTrue();
			assertThat(process.isAlive()).isTrue();
		} finally {
			stop(process);
			reader.join(SHUTDOWN_TIMEOUT.toMillis());
		}
	}

	private void configureEnvironment(Map<String, String> environment) {
		environment.put("SPRING_PROFILES_ACTIVE", "prod");
		environment.put("LISTEN_PORT", "0");
		environment.put("NETWORK", "MAINNET");
		environment.put("BENEFICIARY_ADDRESS", "0x0000000000000000000000000000000000000000");
		environment.put("EXPLORER_ENABLE", "true");
		environment.put("POSTGRESQL_ENABLE", "true");
		environment.put("WEBHOOK_ENABLE", "true");
		environment.put("POSTGRESQL_HOST", POSTGRES.getHost());
		environment.put("POSTGRESQL_PORT", Integer.toString(POSTGRES.getMappedPort(5432)));
		environment.put("POSTGRESQL_DB_NAME", POSTGRES.getDatabaseName());
		environment.put("POSTGRESQL_USERNAME", POSTGRES.getUsername());
		environment.put("POSTGRESQL_PASSWORD", POSTGRES.getPassword());
		environment.put("BLOCKCHAIN_DB_PATH", temporaryDirectory.resolve("blockchain").toString());
		environment.put("PEER_REPUTATION_DB_PATH", temporaryDirectory.resolve("peer-reputation").toString());
		environment.put("NODE_IDENTITY_FILE", temporaryDirectory.resolve("node-identity").toString());
		environment.put("LOGGING_DIR", temporaryDirectory.resolve("logs").toString());
		environment.put("LOGGING_FILE", "startup-smoke.log");
		environment.put("MEMPOOL_MAX_SIZE", "1000");
		environment.put("MEMPOOL_EXPIRE_TX_IN_MINUTES", "60");
		environment.put("MEMPOOL_MIN_ACCEPTABLE_FEE_IN_WEI", "10");
		environment.put("MEMPOOL_MAX_NONCE_GAP_PER_SENDER", "64");
		environment.put("DIRECTORY_PING_INTERVAL_IN_MS", "30000");
		environment.put("P2P_HOST", "127.0.0.1");
		environment.put("P2P_PORT", "0");
		environment.put("MINING_ENABLE", "false");
		environment.put("MINING_HASHING_THREADS", "-1");
		environment.put("MINING_MEMORY_MODE", "FULL");
		environment.put("SYNC_RANDOMX_VERIFICATION_MODE", "LIGHT");
		environment.put("SNAPSHOT_BOOTSTRAP_ENABLED", "false");
		environment.put("SNAPSHOT_PUBLISH_ENABLED", "false");
		environment.put("SECURITY_HMAC_SECRET", secret((byte) 0x11));
		environment.put("SECURITY_AES_GCM_SECRET", secret((byte) 0x22));
		environment.put("SECURITY_CORE_API_ENABLED", "false");
		environment.put("SECURITY_EXPLORER_API_ENABLED", "true");
		environment.put("ADMIN_USERNAME", "admin");
		environment.put("ADMIN_PASSWORD", "startup-smoke");
		environment.put("THROTTLING_GLOBAL_CAPACITY", "500");
		environment.put("THROTTLING_GLOBAL_REFILL_TOKENS", "500");
		environment.put("THROTTLING_PUBLIC_CORE_CAPACITY", "100");
		environment.put("THROTTLING_PUBLIC_CORE_REFILL_TOKENS", "20");
		environment.put("THROTTLING_API_KEY_DEFAULT_CAPACITY", "5000");
		environment.put("THROTTLING_API_KEY_DEFAULT_REFILL_TOKENS", "2000");
		environment.put("THROTTLING_API_KEY_EXPLORER_CAPACITY", "500");
		environment.put("THROTTLING_API_KEY_EXPLORER_REFILL_TOKENS", "100");
		environment.put("THROTTLING_P2P_CAPACITY", "20000");
		environment.put("THROTTLING_P2P_REFILL_TOKENS", "10000");
		environment.put("ROCKSDB_BLOCK_CACHE_MB", "16");
		environment.put("ROCKSDB_WRITE_BUFFER_MB", "8");
		environment.put("ROCKSDB_DIRECT_READS", "false");
		environment.put("ROCKSDB_DIRECT_WRITES", "false");
		environment.put("CACHE_BLOCK_MB", "16");
		environment.put("CACHE_TRIE_NODE_MB", "16");
		environment.put("CACHE_TX_MB", "8");
	}

	private static void readOutput(
			Process process,
			StringBuilder output,
			AtomicBoolean springStarted,
			AtomicBoolean coreReady,
			CountDownLatch ready) {
		try (BufferedReader lines = new BufferedReader(new InputStreamReader(
				process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = lines.readLine()) != null) {
				synchronized (output) {
					output.append(line).append(System.lineSeparator());
				}
				if (line.contains(STARTED_MARKER)) {
					springStarted.set(true);
				}
				if (line.contains(CORE_READY_MARKER)) {
					coreReady.set(true);
				}
				if (springStarted.get() && coreReady.get()) {
					ready.countDown();
				}
			}
		} catch (IOException exception) {
			if (process.isAlive()) {
				synchronized (output) {
					output.append("Cannot read application output: ").append(exception).append(System.lineSeparator());
				}
			}
		}
	}

	private static boolean awaitReadyOrExit(Process process, CountDownLatch ready, Duration timeout)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (ready.await(250, TimeUnit.MILLISECONDS)) {
				return true;
			}
			if (!process.isAlive()) {
				return false;
			}
		}
		return false;
	}

	private static void stop(Process process) throws InterruptedException {
		if (!process.isAlive()) {
			return;
		}
		process.destroy();
		if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	private static String productionClasspath() {
		String classpath = System.getProperty(
				"surefire.test.class.path", System.getProperty("java.class.path"));
		return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
				.filter(entry -> !entry.endsWith("target/test-classes"))
				.collect(Collectors.joining(File.pathSeparator));
	}

	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private static String secret(byte value) {
		byte[] secret = new byte[32];
		Arrays.fill(secret, value);
		return Base64.getEncoder().encodeToString(secret);
	}

	private static String outputTail(StringBuilder output) {
		synchronized (output) {
			int start = Math.max(0, output.length() - 20_000);
			return output.substring(start);
		}
	}
}
