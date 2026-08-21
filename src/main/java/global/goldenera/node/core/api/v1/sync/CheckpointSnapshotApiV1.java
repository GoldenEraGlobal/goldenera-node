/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.api.v1.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.READ;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import global.goldenera.node.core.properties.SnapshotDistributionProperties;

/** Serves only immutable, pre-created snapshot artifacts from an opt-in directory. */
@RestController
@RequestMapping("/api/core/v1/sync/snapshots/checkpoint")
public class CheckpointSnapshotApiV1 {

	private final SnapshotDistributionProperties properties;

	public CheckpointSnapshotApiV1(SnapshotDistributionProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/manifest")
	public ResponseEntity<StreamingResponseBody> manifest() {
		return serve("manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> chunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("chunk-%05d.bin".formatted(parsed), properties.getMaxChunkBytes(), true,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/archive/manifest")
	public ResponseEntity<StreamingResponseBody> archiveManifest() {
		return serve("archive-manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/archive/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> archiveChunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("archive-chunk-%05d.bin".formatted(parsed), properties.getMaxArchiveChunkBytes(), true,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	private Integer parseIndex(String index, int limit) {
		if (index == null || !index.matches("0|[1-9][0-9]{0,4}")) {
			return null;
		}
		int parsed = Integer.parseInt(index);
		return parsed < limit ? parsed : null;
	}

	private ResponseEntity<StreamingResponseBody> serve(
			String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		if (!properties.isPublishEnabled() || properties.getPublishDirectory() == null) {
			return ResponseEntity.notFound().build();
		}
		try {
			properties.validate();
			Path configuredBase = properties.getPublishDirectory().toAbsolutePath().normalize();
			if (hasSymlinkComponent(configuredBase)) {
				return ResponseEntity.notFound().build();
			}
			Path base = configuredBase.toRealPath(LinkOption.NOFOLLOW_LINKS);
			if (!base.equals(configuredBase)) {
				return ResponseEntity.notFound().build();
			}
			Path candidate = base.resolve(fileName).normalize();
			if (!candidate.getParent().equals(base) || Files.isSymbolicLink(candidate)
					|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
				return ResponseEntity.notFound().build();
			}
			Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
			FileChannel channel = FileChannel.open(real, READ, LinkOption.NOFOLLOW_LINKS);
			long size = channel.size();
			if (!real.getParent().equals(base) || size < 0 || size > maxBytes) {
				channel.close();
				return ResponseEntity.notFound().build();
			}
			StreamingResponseBody body = output -> {
				try (channel; InputStream input = Channels.newInputStream(channel)) {
					input.transferTo(output);
				}
			};
			return ResponseEntity.ok()
					.contentType(contentType)
					.contentLength(size)
					.cacheControl(immutable
							? CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable()
							: CacheControl.noCache())
					.body(body);
		} catch (IOException | RuntimeException e) {
			return ResponseEntity.notFound().build();
		}
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
