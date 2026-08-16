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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestException;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;

class SandboxManifestAuthoringServiceTest {

	private static final String FIXTURE_HASH =
			"0xcc97ffd200afec82890413f8bb113d9858619f65ea5f58ef6a3bcd9fc7b902df";
	private static final String FIXTURE_FINGERPRINT =
			"389180aae949eceb2103cbde98f87b484afdc7e86ddf33c4605dcf9a7e645ce3";

	private final SandboxManifestAuthoringService service = new SandboxManifestAuthoringService();

	@TempDir
	Path temporaryDirectory;

	@BeforeEach
	void canonicalizeTemporaryDirectory() throws IOException {
		temporaryDirectory = temporaryDirectory.toRealPath();
	}

	@Test
	void knownDraftProducesCanonicalReloadableVerifiedFixture() throws Exception {
		Path output = temporaryDirectory.resolve("manifest.json");

		SandboxManifestAuthoringResult result = service.author(draft("draft.json"), output);
		SandboxManifestContext reloaded = new SandboxManifestLoader().load(output);

		assertThat(result.genesisHash()).isEqualTo(FIXTURE_HASH);
		assertThat(result.manifestFingerprint()).isEqualTo(FIXTURE_FINGERPRINT);
		assertThat(reloaded.manifest().genesis().expectedGenesisHash()).isEqualTo(FIXTURE_HASH);
		assertThat(result.canonicalManifest()).isEqualTo(Files.readAllBytes(output));
		assertThat(result.manifestFingerprint()).isEqualTo(reloaded.fingerprint());
	}

	@Test
	void sameDraftIsIdempotentAcrossIndependentOutputs() throws Exception {
		Path draft = draft("draft.json");

		SandboxManifestAuthoringResult first = service.author(draft, temporaryDirectory.resolve("first.json"));
		SandboxManifestAuthoringResult second = service.author(draft, temporaryDirectory.resolve("second.json"));

		assertThat(first.genesisHash()).isEqualTo(second.genesisHash());
		assertThat(first.manifestFingerprint()).isEqualTo(second.manifestFingerprint());
		assertThat(first.canonicalManifest()).isEqualTo(second.canonicalManifest());
	}

	@Test
	void refusesOverwriteAndPreservesExistingBytes() throws Exception {
		Path output = temporaryDirectory.resolve("existing.json");
		Files.writeString(output, "owned-by-caller", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.author(draft("draft.json"), output))
				.isInstanceOf(SandboxManifestAuthoringException.class)
				.hasMessageContaining("refusing to overwrite");
		assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo("owned-by-caller");
	}

	@Test
	void rejectsRelativeAndInvalidDraftWithoutPublishingOutput() throws Exception {
		Path output = temporaryDirectory.resolve("output.json");
		Path invalid = temporaryDirectory.resolve("invalid.json");
		Files.writeString(invalid, "{}", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.author(Path.of("draft.json"), output))
				.isInstanceOf(SandboxManifestAuthoringException.class)
				.hasMessageContaining("must be absolute");
		assertThatThrownBy(() -> service.author(invalid, output)).isInstanceOf(SandboxManifestException.class);
		assertThat(output).doesNotExist();
	}

	@Test
	void concurrentPublishersNeverOverwriteTheWinner() throws Exception {
		Path firstDraft = draft("first-draft.json");
		Path secondDraft = draft("second-draft.json", "sandbox-ffeeddccbbaa99887766554433221100");
		Path output = temporaryDirectory.resolve("winner.json");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Attempt> first = executor.submit(() -> attempt(start, firstDraft, output));
			Future<Attempt> second = executor.submit(() -> attempt(start, secondDraft, output));
			start.countDown();
			List<Attempt> attempts = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
			List<Attempt> successes = attempts.stream().filter(attempt -> attempt.result() != null).toList();
			List<Attempt> failures = attempts.stream().filter(attempt -> attempt.failure() != null).toList();

			assertThat(successes).hasSize(1);
			assertThat(failures).hasSize(1);
			assertThat(failures.get(0).failure())
					.isInstanceOf(SandboxManifestAuthoringException.class)
					.hasMessageContaining("refusing to overwrite");
			assertThat(Files.readAllBytes(output)).isEqualTo(successes.get(0).result().canonicalManifest());
		} finally {
			executor.shutdownNow();
		}
	}

	private Path draft(String name) throws IOException {
		return draft(name, "sandbox-00112233445566778899aabbccddeeff");
	}

	private Path draft(String name, String chainId) throws IOException {
		String draft = resource().replace(
				"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				SandboxManifestLoader.AUTHORING_GENESIS_HASH_PLACEHOLDER)
				.replace("sandbox-00112233445566778899aabbccddeeff", chainId);
		Path path = temporaryDirectory.resolve(name);
		Files.writeString(path, draft, StandardCharsets.UTF_8);
		return path;
	}

	private String resource() throws IOException {
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private Attempt attempt(CountDownLatch start, Path draft, Path output) {
		try {
			start.await();
			return new Attempt(service.author(draft, output), null);
		} catch (Throwable failure) {
			return new Attempt(null, failure);
		}
	}

	private record Attempt(SandboxManifestAuthoringResult result, Throwable failure) {
	}
}
