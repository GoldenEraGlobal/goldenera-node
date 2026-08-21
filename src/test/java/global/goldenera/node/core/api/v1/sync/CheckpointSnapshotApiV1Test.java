/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.api.v1.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
	}

	@Test
	void servesOnlyPrecreatedFixedManifestAndStrictNumericChunkPath() throws Exception {
		byte[] manifest = "manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] chunk = "chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] archiveManifest = "archive-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] archiveChunk = "archive-chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(publishDirectory.resolve("manifest.json"), manifest);
		Files.write(publishDirectory.resolve("chunk-00000.bin"), chunk);
		Files.write(publishDirectory.resolve("archive-manifest.json"), archiveManifest);
		Files.write(publishDirectory.resolve("archive-chunk-00000.bin"), archiveChunk);
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(publishDirectory);
		CheckpointSnapshotApiV1 controller = new CheckpointSnapshotApiV1(properties);

		ResponseEntity<StreamingResponseBody> manifestResponse = controller.manifest();
		ResponseEntity<StreamingResponseBody> chunkResponse = controller.chunk("0");
		assertThat(manifestResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(chunkResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
		assertThat(body(manifestResponse)).isEqualTo(manifest);
		assertThat(body(chunkResponse)).isEqualTo(chunk);
		assertThat(body(controller.archiveManifest())).isEqualTo(archiveManifest);
		assertThat(body(controller.archiveChunk("0"))).isEqualTo(archiveChunk);
		assertThat(controller.chunk("00").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.chunk("../0").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.chunk("4096").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveChunk("00").getStatusCode().value()).isEqualTo(404);
		assertThat(controller.archiveChunk("16384").getStatusCode().value()).isEqualTo(404);
	}

	private byte[] body(ResponseEntity<StreamingResponseBody> response) throws Exception {
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		response.getBody().writeTo(output);
		return output.toByteArray();
	}
}
