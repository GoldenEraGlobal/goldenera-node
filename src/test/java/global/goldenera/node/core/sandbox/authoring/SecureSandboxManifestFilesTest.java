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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureSandboxManifestFilesTest {

	private final SecureSandboxManifestFiles files = new SecureSandboxManifestFiles();

	@TempDir
	Path temporaryDirectory;

	@BeforeEach
	void canonicalizeTemporaryDirectory() throws IOException {
		temporaryDirectory = temporaryDirectory.toRealPath();
	}

	@Test
	void rejectsSymlinkInAnyDraftOrOutputAncestor() throws Exception {
		Path real = Files.createDirectory(temporaryDirectory.resolve("real"));
		Path nested = Files.createDirectory(real.resolve("nested"));
		Path draft = nested.resolve("draft.json");
		Files.writeString(draft, "{}", StandardCharsets.UTF_8);
		Path alias = temporaryDirectory.resolve("alias");
		Files.createSymbolicLink(alias, real);

		assertThatThrownBy(() -> files.readDraft(alias.resolve("nested/draft.json")))
				.isInstanceOf(SandboxManifestAuthoringException.class);
		assertThatThrownBy(() -> files.publish(alias.resolve("nested/output.json"), new byte[]{1, 2, 3}))
				.isInstanceOf(SandboxManifestAuthoringException.class);
	}

	@Test
	void rejectsPathsWhichAreNotAlreadyNormalized() {
		Path nonNormalized = temporaryDirectory.resolve("child/../manifest.json");

		assertThatThrownBy(() -> files.requireStrictPath(nonNormalized, "Manifest"))
				.isInstanceOf(SandboxManifestAuthoringException.class)
				.hasMessageContaining("already be normalized");
	}
}
