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
package global.goldenera.node.explorer.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.publication.VerifiedCorePublication;
import global.goldenera.node.core.sync.snapshot.publication.SnapshotPublicationAnchor;

class DelayedExplorerSnapshotPublicationGeneratorTest {

	private static final long HEIGHT = 123;
	private static final Hash HASH = Hash.fromHexString("0x" + "1".repeat(64));
	private static final Hash ROOT = Hash.fromHexString("0x" + "2".repeat(64));
	private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
			1, 1, "test", "0x" + "3".repeat(64), "4".repeat(64));
	private static final Instant CAPTURED = Instant.parse("2026-08-20T00:00:00Z");

	@TempDir
	Path temporaryDirectory;

	@Test
	void firstDayPublishesCoreOnlyAndPersistsCaptureAcrossRestart() throws Exception {
		Fixture fixture = fixture(CAPTURED, HASH, HEIGHT);
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));

		assertThat(fixture.generator().isExactlyCaughtUp(core)).isFalse();
		assertThat(fixture.captures()).hasValue(1);
		assertThat(pendingDirectories()).hasSize(1);

		Fixture restarted = fixture(CAPTURED.plus(Duration.ofHours(25)), HASH, HEIGHT + 1);
		assertThat(restarted.generator().isExactlyCaughtUp(core)).isTrue();
		assertThat(restarted.captures()).hasValue(0);
	}

	@Test
	void restartRejectsUnsupportedPendingFormatAndRecapturesOnlyCurrentFormat() throws Exception {
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));
		assertThat(fixture(CAPTURED, HASH, HEIGHT).generator().isExactlyCaughtUp(core)).isFalse();
		Path manifestFile = pendingDirectories().getFirst().resolve("bundle")
				.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME);
		ExplorerSnapshotManifestCodec codec = new ExplorerSnapshotManifestCodec(new ObjectMapper());
		ExplorerSnapshotManifest current = codec.decode(Files.readAllBytes(manifestFile));
		ExplorerSnapshotManifest unsupported = codec.sign(new ExplorerSnapshotManifest(
				2, current.carrierNetworkCode(), current.chainId(), current.genesisHash(),
				current.checkpointHeight(), current.checkpointHash(), current.checkpointStateRoot(),
				current.coreStateSigningHash(), current.coreArchiveSigningHash(),
				current.explorerMigrationFingerprint(), current.tableSchemaVersions(),
				current.tableRowCounts(), current.chunks(), null));
		Files.write(manifestFile, codec.encode(unsupported));

		Fixture restarted = fixture(CAPTURED.plus(Duration.ofHours(25)), HASH, HEIGHT + 1);
		assertThat(restarted.generator().isExactlyCaughtUp(core)).isFalse();
		assertThat(restarted.captures()).hasValue(1);
		assertThat(pendingDirectories()).isEmpty();
	}

	@Test
	void matureCaptureIsReboundExactlyToGeneratedCoreHashes() throws Exception {
		Fixture first = fixture(CAPTURED, HASH, HEIGHT);
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));
		assertThat(first.generator().isExactlyCaughtUp(core)).isFalse();

		ExplorerSnapshotBinding finalBinding = binding("5".repeat(64), "6".repeat(64));
		Fixture mature = fixture(CAPTURED.plus(Duration.ofHours(25)), HASH, HEIGHT + 1, finalBinding);
		Path output = temporaryDirectory.resolve("final-explorer");
		assertThat(mature.generator().isExactlyCaughtUp(core)).isTrue();
		mature.generator().generate(core, output);

		ExplorerSnapshotManifest manifest = new ExplorerSnapshotManifestCodec(new ObjectMapper())
				.decode(Files.readAllBytes(output.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME)));
		assertThat(manifest.coreStateSigningHash()).isEqualTo(finalBinding.coreStateSigningHash());
		assertThat(manifest.coreArchiveSigningHash()).isEqualTo(finalBinding.coreArchiveSigningHash());
		assertThat(manifest.checkpointHeight()).isEqualTo(HEIGHT);
		assertThat(manifest.checkpointHash()).isEqualTo(HASH.toHexString());
	}

	@Test
	void matureCaptureSelectsItsExactHeightWhenItIsBehindMaximumSafeCoreAnchor() throws Exception {
		Fixture first = fixture(CAPTURED, HASH, HEIGHT);
		SnapshotPublicationAnchor maximum = new SnapshotPublicationAnchor(HEIGHT + 20, hash(9), 10);
		assertThat(first.generator().preferredCoreAnchor(maximum)).isEmpty();

		Fixture mature = fixture(CAPTURED.plus(Duration.ofHours(25)), HASH, HEIGHT + 1);
		assertThat(mature.generator().preferredCoreAnchor(maximum))
				.contains(new SnapshotPublicationAnchor(HEIGHT, HASH, maximum.lagBlocks()));
	}

	@Test
	void reorgInvalidatesOldCaptureAndLaggingExplorerDoesNotLeaveReplacement() throws Exception {
		Fixture first = fixture(CAPTURED, HASH, HEIGHT);
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));
		assertThat(first.generator().isExactlyCaughtUp(core)).isFalse();
		Hash replacement = Hash.fromHexString("0x" + "7".repeat(64));

		Fixture reorged = fixture(CAPTURED.plus(Duration.ofHours(25)), replacement, HEIGHT + 1);
		assertThat(reorged.generator().isExactlyCaughtUp(core)).isFalse();
		assertThat(pendingDirectories()).isEmpty();
	}

	@Test
	void canonicalAgeMaturesAtTheExactTwentyFourHourBoundary() throws Exception {
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));
		assertThat(fixture(CAPTURED, HASH, HEIGHT).generator().isExactlyCaughtUp(core)).isFalse();

		assertThat(fixture(CAPTURED.plus(Duration.ofHours(24)), HASH, HEIGHT + 1)
				.generator().isExactlyCaughtUp(core)).isTrue();
	}

	@Test
	void tooYoungOrNonMonotonicCanonicalTimestampCannotMatureCapture() throws Exception {
		VerifiedCorePublication core = new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core"));
		fixture(CAPTURED, HASH, HEIGHT).generator().isExactlyCaughtUp(core);

		assertThat(fixture(CAPTURED.plus(Duration.ofHours(24)).minusMillis(1), HASH, HEIGHT + 1)
				.generator().isExactlyCaughtUp(core)).isFalse();
		assertThat(fixture(CAPTURED.minusMillis(1), HASH, HEIGHT + 1)
				.generator().isExactlyCaughtUp(core)).isFalse();
	}

	@Test
	void disabledModePerformsZeroDatabaseChainIdentityOrFilesystemInteractions() throws Exception {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishDirectory(temporaryDirectory.resolve("disabled-root"));
		DataSource dataSource = mock(DataSource.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		AuthoritativeChainIdentityProvider identity = mock(AuthoritativeChainIdentityProvider.class);
		AtomicInteger captures = new AtomicInteger();
		DelayedExplorerSnapshotPublicationGenerator generator = new DelayedExplorerSnapshotPublicationGenerator(
				false, dataSource, chainQuery, identity, properties, new ObjectMapper(), Clock.systemUTC(),
				publication -> binding("5".repeat(64), "6".repeat(64)),
				(bundle, storedIdentity, chunkBytes) -> {
					captures.incrementAndGet();
					return new DelayedExplorerSnapshotPublicationGenerator.CaptureAnchor(
							HEIGHT, HASH, ROOT, CAPTURED);
				});

		assertThat(generator.isExactlyCaughtUp(
				new VerifiedCorePublication(HEIGHT, HASH, temporaryDirectory.resolve("core")))).isFalse();
		assertThat(captures).hasValue(0);
		verify(dataSource, never()).getConnection();
		verify(chainQuery, never()).getLatestStoredBlockOrThrow();
		verify(identity, never()).identity();
		assertThat(properties.getPublishDirectory()).doesNotExist();
	}

	private Fixture fixture(Instant now, Hash canonicalHash, long canonicalHeight) throws Exception {
		return fixture(now, canonicalHash, canonicalHeight, binding("5".repeat(64), "6".repeat(64)));
	}

	private Fixture fixture(
			Instant now,
			Hash canonicalHash,
			long canonicalHeight,
			ExplorerSnapshotBinding finalBinding) throws Exception {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishDirectory(temporaryDirectory.resolve("publication"));
		properties.setMaxArchiveChunkBytes(8 * 1024 * 1024);
		Files.createDirectories(properties.getPublishDirectory());
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock captured = stored(HEIGHT, canonicalHash, ROOT, CAPTURED);
		StoredBlock head = canonicalHeight == HEIGHT
				? captured : stored(canonicalHeight, hash(8), ROOT, now);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(head);
		when(chainQuery.getStoredBlockByHeight(HEIGHT))
				.thenReturn(Optional.of(captured));
		AuthoritativeChainIdentityProvider identity = mock(AuthoritativeChainIdentityProvider.class);
		when(identity.identity()).thenReturn(IDENTITY);
		AtomicInteger captures = new AtomicInteger();
		DelayedExplorerSnapshotPublicationGenerator.CaptureBackend backend = (bundle, ignored, chunkBytes) -> {
			captures.incrementAndGet();
			writeBundle(bundle, binding("0".repeat(64), "0".repeat(64)));
			return new DelayedExplorerSnapshotPublicationGenerator.CaptureAnchor(HEIGHT, HASH, ROOT, CAPTURED);
		};
		DelayedExplorerSnapshotPublicationGenerator generator = new DelayedExplorerSnapshotPublicationGenerator(
				true, mock(DataSource.class), chainQuery, identity, properties, new ObjectMapper(),
				Clock.fixed(now, ZoneOffset.UTC), publication -> finalBinding, backend);
		return new Fixture(generator, captures);
	}

	private void writeBundle(Path bundle, ExplorerSnapshotBinding binding) throws Exception {
		Files.createDirectory(bundle);
		List<ExplorerSnapshotChunkDescriptor> chunks = new ArrayList<>();
		Map<String, Integer> versions = new HashMap<>();
		Map<String, Long> counts = new HashMap<>();
		List<ExplorerSnapshotColumn> columns = List.of(
				new ExplorerSnapshotColumn("id", ExplorerSnapshotValueType.INT32, true));
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			byte[] bytes = ExplorerSnapshotChunkCodec.encodeHeader(table, columns, 0);
			String fileName = "explorer-" + table.name().toLowerCase() + "-0.bin";
			Files.write(bundle.resolve(fileName), bytes);
			chunks.add(new ExplorerSnapshotChunkDescriptor(
					table, ExplorerSnapshotTable.SCHEMA_VERSION, 0, 0, bytes.length,
					ExplorerSnapshotDigests.sha256(bytes), fileName));
			versions.put(table.tableName(), ExplorerSnapshotTable.SCHEMA_VERSION);
			counts.put(table.tableName(), 0L);
		}
		ExplorerSnapshotManifest manifest = new ExplorerSnapshotManifestCodec(new ObjectMapper()).sign(
				new ExplorerSnapshotManifest(
						ExplorerSnapshotManifest.FORMAT_VERSION, binding.carrierNetworkCode(), binding.chainId(),
						binding.genesisHash(), binding.checkpointHeight(), binding.checkpointHash(),
						binding.checkpointStateRoot(), binding.coreStateSigningHash(),
						binding.coreArchiveSigningHash(), "8".repeat(64), versions, counts, chunks, null));
		Files.write(bundle.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME),
				new ExplorerSnapshotManifestCodec(new ObjectMapper()).encode(manifest));
	}

	private ExplorerSnapshotBinding binding(String stateHash, String archiveHash) {
		return new ExplorerSnapshotBinding(
				1, IDENTITY.chainId(), IDENTITY.genesisHash(), HEIGHT, HASH.toHexString(), ROOT.toHexString(),
				stateHash, archiveHash);
	}

	private StoredBlock stored(long height, Hash hash, Hash stateRoot, Instant timestamp) {
		StoredBlock stored = mock(StoredBlock.class);
		Block block = mock(Block.class);
		BlockHeader header = mock(BlockHeader.class);
		when(stored.getHeight()).thenReturn(height);
		when(stored.getHash()).thenReturn(hash);
		when(stored.getBlock()).thenReturn(block);
		when(block.getHeader()).thenReturn(header);
		when(header.getStateRootHash()).thenReturn(stateRoot);
		when(header.getTimestamp()).thenReturn(timestamp);
		return stored;
	}

	private Hash hash(int value) {
		return Hash.fromHexString("0x" + "%02x".formatted(value).repeat(32));
	}

	private List<Path> pendingDirectories() throws Exception {
		Path root = temporaryDirectory.resolve("publication/.publisher/explorer-pending");
		if (Files.notExists(root)) {
			return List.of();
		}
		try (var entries = Files.list(root)) {
			return entries.filter(path -> path.getFileName().toString().startsWith("pending-")).toList();
		}
	}

	private record Fixture(
			DelayedExplorerSnapshotPublicationGenerator generator,
			AtomicInteger captures) {
	}

}
