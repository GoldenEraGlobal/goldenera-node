/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.properties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.Constants;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.core.sync.snapshot", ignoreUnknownFields = false)
public class SnapshotDistributionProperties {

	public static final long HARD_MAX_MANIFEST_BYTES = 8L * 1024 * 1024;
	public static final int HARD_MAX_PARALLELISM = 16;
	public static final Duration HARD_MAX_CONNECT_TIMEOUT = Duration.ofMinutes(5);
	public static final Duration HARD_MAX_READ_TIMEOUT = Duration.ofHours(2);
	public static final Duration HARD_MAX_CALL_TIMEOUT = Duration.ofHours(4);

	boolean bootstrapEnabled;
	boolean publishEnabled;
	boolean allowHttpForTesting;
	Path publishDirectory;
	Path stagingDirectory;
	long maxManifestBytes = 8L * 1024 * 1024;
	long maxChunkBytes = CheckpointSnapshotLimits.MAX_CHUNK_BYTES;
	long maxTotalBytes = CheckpointSnapshotLimits.MAX_TOTAL_BYTES;
	int maxChunkCount = CheckpointSnapshotLimits.MAX_CHUNK_COUNT;
	long maxArchiveChunkBytes = CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES;
	long maxArchiveTotalBytes = CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES;
	int maxArchiveChunkCount = CoreSnapshotArchiveLimits.MAX_CHUNK_COUNT;
	int parallelism = 2;
	Duration connectTimeout = Duration.ofSeconds(5);
	Duration readTimeout = Duration.ofMinutes(2);
	Duration callTimeout = Duration.ofMinutes(5);
	Map<Network, List<URI>> trustedSources = defaultTrustedSources();

	public List<URI> trustedSources(Network network) {
		return List.copyOf(trustedSources.getOrDefault(network, List.of()));
	}

	public void validate() {
		if (maxManifestBytes <= 0 || maxChunkBytes <= 0 || maxTotalBytes <= 0 || maxChunkCount <= 0
				|| maxArchiveChunkBytes <= 0 || maxArchiveTotalBytes <= 0 || maxArchiveChunkCount <= 0
				|| parallelism <= 0 || connectTimeout.isNegative() || connectTimeout.isZero()
				|| readTimeout.isNegative() || readTimeout.isZero()
				|| callTimeout.isNegative() || callTimeout.isZero()) {
			throw new IllegalArgumentException("Invalid snapshot distribution limits");
		}
		if (maxManifestBytes > HARD_MAX_MANIFEST_BYTES
				|| maxChunkBytes > CheckpointSnapshotLimits.MAX_CHUNK_BYTES
				|| maxTotalBytes > CheckpointSnapshotLimits.MAX_TOTAL_BYTES
				|| maxChunkCount > CheckpointSnapshotLimits.MAX_CHUNK_COUNT
				|| maxArchiveChunkBytes > CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES
				|| maxArchiveTotalBytes > CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES
				|| maxArchiveChunkCount > CoreSnapshotArchiveLimits.MAX_CHUNK_COUNT
				|| parallelism > HARD_MAX_PARALLELISM
				|| connectTimeout.compareTo(HARD_MAX_CONNECT_TIMEOUT) > 0
				|| readTimeout.compareTo(HARD_MAX_READ_TIMEOUT) > 0
				|| callTimeout.compareTo(HARD_MAX_CALL_TIMEOUT) > 0) {
			throw new IllegalArgumentException("Snapshot distribution configuration exceeds hard safety limits");
		}
		if (trustedSources == null) {
			throw new IllegalArgumentException("Snapshot trusted sources are required");
		}
		trustedSources.values().stream().flatMap(List::stream).forEach(this::validateTrustedSource);
		if (publishEnabled && (publishDirectory == null || publishDirectory.toString().isBlank())) {
			throw new IllegalArgumentException("Snapshot publish directory is required when publishing is enabled");
		}
	}

	public void validateTrustedSource(URI source) {
		if (source == null || !source.isAbsolute() || source.getHost() == null || source.getUserInfo() != null
				|| source.getQuery() != null || source.getFragment() != null) {
			throw new IllegalArgumentException("Snapshot source must be an absolute origin without userinfo/query/fragment");
		}
		boolean https = "https".equalsIgnoreCase(source.getScheme());
		boolean testHttp = allowHttpForTesting && "http".equalsIgnoreCase(source.getScheme());
		if (!https && !testHttp) {
			throw new IllegalArgumentException("Snapshot source must use HTTPS");
		}
	}

	private static Map<Network, List<URI>> defaultTrustedSources() {
		Map<Network, List<URI>> sources = new EnumMap<>(Network.class);
		for (Network network : Network.values()) {
			sources.put(network, Constants.getSnapshotDistributionConfig(network).trustedSources());
		}
		return sources;
	}
}
