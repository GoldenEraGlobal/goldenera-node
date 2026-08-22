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
package global.goldenera.node.core.api.v1.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

import global.goldenera.node.core.properties.SnapshotDistributionProperties;

class CheckpointSnapshotApiV1Test {

	@TempDir
	Path publishDirectory;

	@Test
	void publishingIsDisabledByDefault() {
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(
				new SnapshotDistributionProperties());

		assertThat(controller.manifest().getStatusCode().value()).isEqualTo(404);
		assertThat(controller.chunk("0").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveManifest().getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveChunk("0").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveEntityChunk("0").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.explorerManifest().getStatusCode().value()).isEqualTo(404);
		assertThat(controller.explorerChunk("explorer-status-0.bin").getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void servesOnlyPrecreatedFixedManifestAndStrictNumericChunkPath() throws Exception {
		byte[] manifest = "manifest".getBytes(StandardCharsets.UTF_8);
		byte[] chunk = "chunk".getBytes(StandardCharsets.UTF_8);
		byte[] archiveManifest = "archive-manifest".getBytes(StandardCharsets.UTF_8);
		byte[] archiveChunk = "archive-chunk".getBytes(StandardCharsets.UTF_8);
		byte[] entityChunk = "entity-chunk".getBytes(StandardCharsets.UTF_8);
		byte[] explorerManifest = "explorer-manifest".getBytes(StandardCharsets.UTF_8);
		byte[] explorerChunk = "explorer-chunk".getBytes(StandardCharsets.UTF_8);
		Files.write(publishDirectory.resolve("manifest.json"), manifest);
		Files.write(publishDirectory.resolve("chunk-00000.bin"), chunk);
		Files.write(publishDirectory.resolve("archive-manifest.json"), archiveManifest);
		Files.write(publishDirectory.resolve("archive-chunk-00000.bin"), archiveChunk);
		Files.write(publishDirectory.resolve("entity-chunk-00000.zst"), entityChunk);
		Files.write(publishDirectory.resolve("explorer-manifest.json"), explorerManifest);
		Files.write(publishDirectory.resolve("explorer-status-0.bin"), explorerChunk);
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(properties);

		ResponseEntity<Resource> manifestResponse = controller.manifest();
		ResponseEntity<Resource> chunkResponse = controller.chunk("0");
		assertThat(manifestResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(chunkResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
		assertThat(body(manifestResponse)).isEqualTo(manifest);
		assertThat(body(chunkResponse)).isEqualTo(chunk);
		assertThat(body(controller.archiveManifest())).isEqualTo(archiveManifest);
		assertThat(body(controller.archiveChunk("0"))).isEqualTo(archiveChunk);
		assertThat(body(controller.archiveEntityChunk("0"))).isEqualTo(entityChunk);
		assertThat(body(controller.explorerManifest())).isEqualTo(explorerManifest);
		assertThat(body(controller.explorerChunk("explorer-status-0.bin"))).isEqualTo(explorerChunk);
		assertThat(controller.chunk("00").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.chunk("../0").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.chunk("4096").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveChunk("00").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveChunk("16384").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveEntityChunk("00").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.explorerChunk("explorer-status-00.bin").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.explorerChunk("explorer-unknown-0.bin").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.explorerChunk("../explorer-status-0.bin").getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void limitsConcurrentStreamsAndReleasesPermitOnCompletionAndError() throws Exception {
		Files.writeString(publishDirectory.resolve("manifest.json"), "manifest");
		Files.writeString(publishDirectory.resolve("archive-manifest.json"), "archive-manifest");
		Files.writeString(publishDirectory.resolve("chunk-00000.bin"), "chunk");
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
		properties.setMaxConcurrentStreams(1);
		properties.setParallelism(1);
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(
				properties, new SnapshotStreamLimiter(properties));

		ResponseEntity<Resource> held = controller.chunk("0");
		ResponseEntity<Resource> rejected = controller.chunk("0");
		assertThat(rejected.getStatusCode().value()).isEqualTo(429);
		assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
		assertThat(body(held)).isEqualTo("chunk".getBytes(StandardCharsets.UTF_8));
		assertThat(body(controller.chunk("0"))).isEqualTo("chunk".getBytes(StandardCharsets.UTF_8));

		ResponseEntity<Resource> failing = controller.chunk("0");
		try (var input = failing.getBody().getInputStream()) {
			assertThatThrownBy(() -> input.transferTo(new OutputStream() {
				@Override
				public void write(int value) throws IOException {
					throw new IOException("client disconnected");
				}
			})).isInstanceOf(IOException.class).hasMessageContaining("client disconnected");
		}
		assertThat(body(controller.chunk("0"))).isEqualTo("chunk".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void servesImmutableChunksFromExactRetainedVersion() throws Exception {
		String version = "snapshot-123-" + "ab".repeat(32);
		Path versionDirectory = Files.createDirectories(publishDirectory.resolve("versions").resolve(version));
		Files.writeString(versionDirectory.resolve("manifest.json"), "state-manifest");
		Files.writeString(versionDirectory.resolve("archive-manifest.json"), "archive-manifest");
		Files.writeString(versionDirectory.resolve("chunk-00000.bin"), "versioned-chunk");
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(properties);

		ResponseEntity<Resource> response = controller.versionChunk(version, "0");
		assertThat(body(response)).isEqualTo("versioned-chunk".getBytes(StandardCharsets.UTF_8));
		assertThat(response.getHeaders().getCacheControl()).contains("immutable");
		assertThat(controller.versionChunk("../" + version, "0").getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void servesEveryVersionedEntityChunkThroughIndependentLeasedResources() throws Exception {
		String version = "snapshot-0-" + "cd".repeat(32) + "-s1-a2-e1-x1";
		Path versionDirectory = Files.createDirectories(publishDirectory.resolve("versions").resolve(version));
		Files.writeString(versionDirectory.resolve("manifest.json"), "state-manifest");
		Files.writeString(versionDirectory.resolve("archive-manifest.json"), "archive-manifest");
		byte[][] chunks = {
				"entity-zero".getBytes(StandardCharsets.UTF_8),
				"entity-one".getBytes(StandardCharsets.UTF_8),
				"entity-two".getBytes(StandardCharsets.UTF_8)
		};
		for (int index = 0; index < chunks.length; index++) {
			Files.write(versionDirectory.resolve("entity-chunk-%05d.zst".formatted(index)), chunks[index]);
		}
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(properties);

		ResponseEntity<Resource> first = controller.versionArchiveEntityChunk(version, "0");
		ResponseEntity<Resource> second = controller.versionArchiveEntityChunk(version, "1");
		ResponseEntity<Resource> third = controller.versionArchiveEntityChunk(version, "2");

		assertThat(body(first)).isEqualTo(chunks[0]);
		assertThat(body(second)).isEqualTo(chunks[1]);
		assertThat(body(third)).isEqualTo(chunks[2]);
		assertThat(body(controller.versionArchiveEntityChunk(version, "1"))).isEqualTo(chunks[1]);
	}

	@Test
	void servesVersionedEntityChunkSynchronouslyWithoutAsyncDispatch() throws Exception {
		String version = "snapshot-0-" + "ef".repeat(32) + "-s1-a2-e1-x1";
		Path versionDirectory = Files.createDirectories(publishDirectory.resolve("versions").resolve(version));
		Files.writeString(versionDirectory.resolve("manifest.json"), "state-manifest");
		Files.writeString(versionDirectory.resolve("archive-manifest.json"), "archive-manifest");
		byte[] entity = "entity-one".getBytes(StandardCharsets.UTF_8);
		Files.write(versionDirectory.resolve("entity-chunk-00001.zst"), entity);
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));

		standaloneSetup(new CheckpointSnapshotApiV1(properties)).build()
				.perform(get("/api/core/v1/sync/snapshots/checkpoint/versions/{version}/archive/entities/1", version))
				.andExpect(status().isOk())
				.andExpect(request().asyncNotStarted())
				.andExpect(content().bytes(entity));
	}

	private byte[] body(ResponseEntity<Resource> response) throws Exception {
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		try (var input = response.getBody().getInputStream()) {
			return input.readAllBytes();
		}
	}
}
