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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.READ;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.sync.snapshot.publication.SnapshotPublicationDirectorySelector;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotTable;

/** Serves only immutable, pre-created snapshot artifacts from an opt-in directory. */
@RestController
@RequestMapping("/api/core/v1/sync/snapshots/checkpoint")
public class CheckpointSnapshotApiV1 {
	private static final long MAX_EXPLORER_CHUNK_BYTES = 64L * 1024 * 1024 + 64L * 1024;

	private final SnapshotDistributionProperties properties;
	private final SnapshotStreamLimiter streamLimiter;
	private final SnapshotPublicationDirectorySelector publicationSelector;

	public CheckpointSnapshotApiV1(SnapshotDistributionProperties properties) {
		this(properties, new SnapshotStreamLimiter(properties));
	}

	@Autowired
	public CheckpointSnapshotApiV1(
			SnapshotDistributionProperties properties,
			SnapshotStreamLimiter streamLimiter) {
		this.properties = properties;
		this.streamLimiter = streamLimiter;
		this.publicationSelector = new SnapshotPublicationDirectorySelector();
	}

	@GetMapping("/manifest")
	public ResponseEntity<Resource> manifest() {
		return serve("manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/chunks/{index}")
	public ResponseEntity<Resource> chunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("chunk-%05d.bin".formatted(parsed), properties.getMaxChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/archive/manifest")
	public ResponseEntity<Resource> archiveManifest() {
		return serve("archive-manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/archive/chunks/{index}")
	public ResponseEntity<Resource> archiveChunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("archive-chunk-%05d.bin".formatted(parsed), properties.getMaxArchiveChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/archive/entities/{index}")
	public ResponseEntity<Resource> archiveEntityChunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("entity-chunk-%05d.zst".formatted(parsed), properties.getMaxArchiveChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/explorer/manifest")
	public ResponseEntity<Resource> explorerManifest() {
		return serve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME,
				properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/explorer/chunks/{fileName}")
	public ResponseEntity<Resource> explorerChunk(@PathVariable String fileName) {
		if (!isExplorerChunkFileName(fileName)) {
			return ResponseEntity.notFound().build();
		}
		return serve(fileName, Math.min(properties.getMaxArchiveChunkBytes(), MAX_EXPLORER_CHUNK_BYTES), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/manifest")
	public ResponseEntity<Resource> versionManifest(@PathVariable String version) {
		return serveVersion(version, "manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/chunks/{index}")
	public ResponseEntity<Resource> versionChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "chunk-%05d.bin".formatted(parsed), properties.getMaxChunkBytes(), true,
						MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/archive/manifest")
	public ResponseEntity<Resource> versionArchiveManifest(@PathVariable String version) {
		return serveVersion(
				version, "archive-manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/archive/chunks/{index}")
	public ResponseEntity<Resource> versionArchiveChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "archive-chunk-%05d.bin".formatted(parsed),
						properties.getMaxArchiveChunkBytes(), true, MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/archive/entities/{index}")
	public ResponseEntity<Resource> versionArchiveEntityChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "entity-chunk-%05d.zst".formatted(parsed),
						properties.getMaxArchiveChunkBytes(), true, MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/explorer/manifest")
	public ResponseEntity<Resource> versionExplorerManifest(@PathVariable String version) {
		return serveVersion(version, ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME,
				properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/explorer/chunks/{fileName}")
	public ResponseEntity<Resource> versionExplorerChunk(
			@PathVariable String version, @PathVariable String fileName) {
		return !isExplorerChunkFileName(fileName) ? ResponseEntity.notFound().build()
				: serveVersion(version, fileName,
						Math.min(properties.getMaxArchiveChunkBytes(), MAX_EXPLORER_CHUNK_BYTES), true,
						MediaType.APPLICATION_OCTET_STREAM);
	}

	private Integer parseIndex(String index, int limit) {
		if (index == null || !index.matches("0|[1-9][0-9]{0,4}")) {
			return null;
		}
		int parsed = Integer.parseInt(index);
		return parsed < limit ? parsed : null;
	}

	private boolean isExplorerChunkFileName(String fileName) {
		if (fileName == null || !fileName.matches("explorer-[a-z_]+-(0|[1-9][0-9]{0,4})\\.bin")) {
			return false;
		}
		int tableEnd = fileName.lastIndexOf('-');
		String table = fileName.substring("explorer-".length(), tableEnd).toUpperCase(Locale.ROOT);
		try {
			ExplorerSnapshotTable.valueOf(table);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private ResponseEntity<Resource> serve(
			String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		return serveFromBase(null, fileName, maxBytes, immutable, contentType);
	}

	private ResponseEntity<Resource> serveVersion(
			String version, String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		return serveFromBase(version, fileName, maxBytes, immutable, contentType);
	}

	private ResponseEntity<Resource> serveFromBase(
			String version, String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		if (!properties.isPublishEnabled() || properties.getPublishDirectory() == null) {
			return ResponseEntity.notFound().build();
		}
		SnapshotStreamLimiter.Lease streamLease = null;
		try {
			properties.validate();
			Path base = version == null
					? publicationSelector.resolve(properties.getPublishDirectory()).orElse(null)
					: publicationSelector.resolveVersion(properties.getPublishDirectory(), version).orElse(null);
			if (base == null) {
				return ResponseEntity.notFound().build();
			}
			Path candidate = base.resolve(fileName).normalize();
			if (!candidate.getParent().equals(base) || Files.isSymbolicLink(candidate)
					|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
				return ResponseEntity.notFound().build();
			}
			Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
			long size = Files.size(real);
			if (!real.getParent().equals(base) || size < 0 || size > maxBytes) {
				return ResponseEntity.notFound().build();
			}
			streamLease = streamLimiter.tryAcquire();
			if (streamLease == null) {
				return ResponseEntity.status(429).header("Retry-After", "1").build();
			}
			Resource body = new LeasedSnapshotResource(real, size, streamLease);
			streamLease = null;
			return ResponseEntity.ok()
					.contentType(contentType)
					.contentLength(size)
					.cacheControl(immutable
							? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
							: CacheControl.noCache())
					.body(body);
		} catch (IOException | RuntimeException e) {
			if (streamLease != null) {
				streamLease.close();
			}
			return ResponseEntity.notFound().build();
		}
	}

	private static final class LeasedSnapshotResource extends AbstractResource {

		private final Path path;
		private final long size;
		private final SnapshotStreamLimiter.Lease lease;
		private final AtomicBoolean opened = new AtomicBoolean();

		private LeasedSnapshotResource(Path path, long size, SnapshotStreamLimiter.Lease lease) {
			this.path = path;
			this.size = size;
			this.lease = lease;
		}

		@Override
		public String getDescription() {
			return "immutable checkpoint snapshot artifact " + path.getFileName();
		}

		@Override
		public String getFilename() {
			return path.getFileName().toString();
		}

		@Override
		public long contentLength() {
			return size;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			if (!opened.compareAndSet(false, true)) {
				throw new IOException("Snapshot response body can be opened only once");
			}
			try {
				return new LeasedInputStream(Files.newInputStream(path, READ), lease);
			} catch (IOException | RuntimeException e) {
				lease.close();
				throw e;
			}
		}
	}

	private static final class LeasedInputStream extends FilterInputStream {

		private final SnapshotStreamLimiter.Lease lease;
		private final AtomicBoolean closed = new AtomicBoolean();

		private LeasedInputStream(InputStream input, SnapshotStreamLimiter.Lease lease) {
			super(input);
			this.lease = lease;
		}

		@Override
		public void close() throws IOException {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			try {
				super.close();
			} finally {
				lease.close();
			}
		}
	}

}
