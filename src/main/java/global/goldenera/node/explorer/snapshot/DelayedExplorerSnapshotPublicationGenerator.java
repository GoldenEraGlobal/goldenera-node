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

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.publication.ExplorerSnapshotPublicationGenerator;
import global.goldenera.node.core.sync.snapshot.publication.SnapshotPublicationAnchor;
import global.goldenera.node.core.sync.snapshot.publication.VerifiedCorePublication;
import global.goldenera.node.core.sync.snapshot.transport.CoreSnapshotArchiveTransportManifest;
import global.goldenera.node.core.sync.snapshot.transport.SnapshotTransportManifest;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;
import lombok.extern.slf4j.Slf4j;

/** Persistent one-day delayed explorer enrichment for the automatic publisher. */
@Slf4j
public final class DelayedExplorerSnapshotPublicationGenerator
		implements ExplorerSnapshotPublicationGenerator {

	static final Duration MATURITY = Duration.ofHours(24);
	private static final String CONTROL_DIRECTORY = ".publisher/explorer-pending";
	private static final String BUNDLE_DIRECTORY = "bundle";
	private static final String METADATA_FILE = "capture.properties";
	private static final String ZERO_SIGNING_HASH = "0".repeat(64);
	private static final int DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024;

	private final boolean enabled;
	private final DataSource dataSource;
	private final ChainQuery chainQuery;
	private final AuthoritativeChainIdentityProvider identityProvider;
	private final SnapshotDistributionProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final Path pendingRoot;
	private final CoreBindingResolver coreBindingResolver;
	private final CaptureBackend captureBackend;

	public DelayedExplorerSnapshotPublicationGenerator(
			boolean enabled,
			DataSource dataSource,
			ChainQuery chainQuery,
			AuthoritativeChainIdentityProvider identityProvider,
			SnapshotDistributionProperties properties,
			ObjectMapper objectMapper,
			Clock clock) {
		this(enabled, dataSource, chainQuery, identityProvider, properties, objectMapper, clock, null, null);
	}

	DelayedExplorerSnapshotPublicationGenerator(
			boolean enabled,
			DataSource dataSource,
			ChainQuery chainQuery,
			AuthoritativeChainIdentityProvider identityProvider,
			SnapshotDistributionProperties properties,
			ObjectMapper objectMapper,
			Clock clock,
			CoreBindingResolver coreBindingResolver,
			CaptureBackend captureBackend) {
		this.enabled = enabled;
		this.dataSource = dataSource;
		this.chainQuery = chainQuery;
		this.identityProvider = identityProvider;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.coreBindingResolver = coreBindingResolver == null ? this::readCoreBinding : coreBindingResolver;
		this.captureBackend = captureBackend == null ? this::captureFromDatabase : captureBackend;
		this.pendingRoot = properties.getPublishDirectory() == null ? null
				: properties.getPublishDirectory().toAbsolutePath().normalize().resolve(CONTROL_DIRECTORY);
	}

	@Override
	public synchronized Optional<SnapshotPublicationAnchor> preferredCoreAnchor(
			SnapshotPublicationAnchor maximumSafeAnchor) {
		if (!enabled) {
			return Optional.empty();
		}
		Objects.requireNonNull(maximumSafeAnchor, "maximumSafeAnchor");
		try {
			Optional<PendingCapture> pending = loadOrCapturePending();
			if (pending.isEmpty()) {
				return Optional.empty();
			}
			PendingCapture capture = pending.orElseThrow();
			boolean mature = isMature(capture);
			return mature && capture.height() <= maximumSafeAnchor.height()
					? Optional.of(new SnapshotPublicationAnchor(
							capture.height(), capture.hash(), maximumSafeAnchor.lagBlocks()))
					: Optional.empty();
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	@Override
	public synchronized boolean isExactlyCaughtUp(VerifiedCorePublication corePublication) {
		if (!enabled) {
			return false;
		}
		Objects.requireNonNull(corePublication, "corePublication");
		try {
			Optional<PendingCapture> pending = loadOrCapturePending();
			if (pending.isEmpty()) {
				return false;
			}
			PendingCapture capture = pending.orElseThrow();
			return isMature(capture)
					&& capture.height() == corePublication.height()
					&& capture.hash().equals(corePublication.blockHash());
		} catch (Exception e) {
			return false;
		}
	}

	private Optional<PendingCapture> loadOrCapturePending() throws IOException {
		prepareRoot();
		cleanupTemporaryCaptures();
		Optional<PendingCapture> pending = loadPending();
		if (pending.isPresent() && !isCanonical(pending.orElseThrow())) {
			deleteRecursively(pending.orElseThrow().directory());
			pending = Optional.empty();
		}
		if (pending.isEmpty()) {
			captureCurrentHead();
			pending = loadPending();
		}
		return pending;
	}

	@Override
	public synchronized void generate(VerifiedCorePublication corePublication, Path outputDirectory) {
		if (!enabled) {
			return;
		}
		try {
			PendingCapture capture = loadPending()
					.orElseThrow(() -> new ExplorerSnapshotException("No pending explorer capture exists"));
			if (!isMature(capture)
					|| capture.height() != corePublication.height()
					|| !capture.hash().equals(corePublication.blockHash())
					|| !isCanonical(capture)) {
				throw new ExplorerSnapshotException("Pending explorer capture is not mature and canonical");
			}
			ExplorerSnapshotBinding coreBinding = coreBindingResolver.resolve(corePublication);
			if (coreBinding.checkpointHeight() != capture.height()
					|| !coreBinding.checkpointHash().equals(capture.hash().toHexString())
					|| !coreBinding.checkpointStateRoot().equals(capture.stateRoot().toHexString())) {
				throw new ExplorerSnapshotException("Pending explorer capture does not match generated core artifacts");
			}
			materialize(capture, coreBinding, outputDirectory);
		} catch (ExplorerSnapshotException e) {
			throw e;
		} catch (Exception e) {
			throw new ExplorerSnapshotException("Cannot finalize pending explorer capture", e);
		}
	}

	private void captureCurrentHead() throws IOException {
		StoredChainIdentity identity = identityProvider.identity();
		Path temporary = Files.createTempDirectory(pendingRoot, ".capture-").toRealPath();
		boolean completed = false;
		CaptureAnchor anchor;
		try {
			Path bundle = temporary.resolve(BUNDLE_DIRECTORY);
			anchor = captureBackend.capture(
					bundle, identity, Math.toIntExact(Math.min(DEFAULT_CHUNK_BYTES, properties.getMaxArchiveChunkBytes())));
		} catch (Exception e) {
			deleteRecursively(temporary);
			if (e instanceof IOException ioException) {
				throw ioException;
			}
			throw new ExplorerSnapshotException("Cannot capture explorer current head", e);
		}
		StoredBlock canonicalHead = chainQuery.getLatestStoredBlockOrThrow();
		if (anchor.height() != canonicalHead.getHeight() || !anchor.hash().equals(canonicalHead.getHash())
				|| !anchor.stateRoot().equals(canonicalHead.getBlock().getHeader().getStateRootHash())
				|| !anchor.blockTimestamp().equals(canonicalHead.getBlock().getHeader().getTimestamp())) {
			deleteRecursively(temporary);
			return;
		}
		try {
			Instant capturedAt = clock.instant();
			writeMetadata(temporary.resolve(METADATA_FILE), anchor, capturedAt);
			Path target = pendingRoot.resolve(pendingName(anchor, capturedAt));
			if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
				Files.move(temporary, target, ATOMIC_MOVE);
				completed = true;
				log.info("EXPLORER SNAPSHOT: Captured pending PostgreSQL snapshot at height {}; "
						+ "it becomes publishable after 24h of canonical chain time", anchor.height());
			}
		} finally {
			if (!completed) {
				deleteRecursively(temporary);
			}
		}
	}

	private CaptureAnchor captureFromDatabase(
			Path bundle,
			StoredChainIdentity identity,
			int chunkBytes) {
		CaptureAnchor anchor = readExplorerAnchor();
		ExplorerSnapshotBinding provisional = new ExplorerSnapshotBinding(
				identity.carrierNetworkCode(), identity.chainId(), identity.genesisHash(), anchor.height(),
				anchor.hash().toHexString(), anchor.stateRoot().toHexString(),
				ZERO_SIGNING_HASH, ZERO_SIGNING_HASH);
		new ExplorerCheckpointSnapshotExporter(dataSource, objectMapper).export(provisional, bundle, chunkBytes);
		return anchor;
	}

	private CaptureAnchor readExplorerAnchor() {
		try (Connection connection = dataSource.getConnection()) {
			connection.setReadOnly(true);
			connection.setAutoCommit(false);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			try (var statement = connection.prepareStatement("""
					SELECT s.synced_block_height, s.synced_block_hash, h.state_root_hash, h.timestamp
					FROM explorer_status s
					JOIN explorer_block_header h
					  ON h.height = s.synced_block_height AND h.hash = s.synced_block_hash
					WHERE s.id = 1
					"""); ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					throw new ExplorerSnapshotException("Explorer current indexed head is unavailable");
				}
				CaptureAnchor anchor = new CaptureAnchor(
						resultSet.getLong(1), Hash.wrap(resultSet.getBytes(2)), Hash.wrap(resultSet.getBytes(3)),
						resultSet.getTimestamp(4).toInstant());
				if (resultSet.next()) {
					throw new ExplorerSnapshotException("Explorer status is not unique");
				}
				connection.rollback();
				return anchor;
			}
		} catch (SQLException e) {
			throw new ExplorerSnapshotException("Cannot capture explorer current indexed head", e);
		}
	}

	private Optional<PendingCapture> loadPending() throws IOException {
		try (var entries = Files.list(pendingRoot)) {
			List<Path> pending = entries
					.filter(path -> path.getFileName().toString().startsWith("pending-"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
			for (int index = 0; index < Math.max(0, pending.size() - 1); index++) {
				deleteRecursively(pending.get(index));
			}
			if (pending.isEmpty()) {
				return Optional.empty();
			}
			Path directory = pending.getLast();
			try {
				Properties metadata = new Properties();
				try (var input = Files.newInputStream(directory.resolve(METADATA_FILE))) {
					metadata.load(input);
				}
				PendingCapture capture = new PendingCapture(
						Long.parseLong(metadata.getProperty("height")),
						Hash.fromHexString(metadata.getProperty("hash")),
						Hash.fromHexString(metadata.getProperty("stateRoot")),
						Instant.ofEpochMilli(Long.parseLong(metadata.getProperty("blockTimestampMillis"))),
						Instant.ofEpochMilli(Long.parseLong(metadata.getProperty("capturedAtMillis"))), directory);
				validatePendingBundle(capture);
				return Optional.of(capture);
			} catch (Exception e) {
				deleteRecursively(directory);
				return Optional.empty();
			}
		}
	}

	private void validatePendingBundle(PendingCapture capture) throws IOException {
		Path bundle = capture.directory().resolve(BUNDLE_DIRECTORY);
		Path manifestFile = bundle.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME);
		if (!regular(manifestFile)) {
			throw new ExplorerSnapshotException("Pending explorer manifest is missing");
		}
		ExplorerSnapshotManifest manifest = new ExplorerSnapshotManifestCodec(objectMapper)
				.decode(Files.readAllBytes(manifestFile));
		ExplorerSnapshotManifestCodec codec = new ExplorerSnapshotManifestCodec(objectMapper);
		if (manifest.formatVersion() != SnapshotFormatCompatibility.CURRENT_EXPLORER_FORMAT
				|| !codec.hasValidSigningHash(manifest)
				|| manifest.checkpointHeight() != capture.height()
				|| !manifest.checkpointHash().equals(capture.hash().toHexString())
				|| !manifest.checkpointStateRoot().equals(capture.stateRoot().toHexString())) {
			throw new ExplorerSnapshotException("Pending explorer metadata does not match its manifest");
		}
		for (ExplorerSnapshotChunkDescriptor chunk : manifest.chunks()) {
			Path path = safeChunk(bundle, chunk.fileName());
			if (Files.size(path) != chunk.uncompressedSize()
					|| !sha256(path).equals(chunk.sha256())) {
				throw new ExplorerSnapshotException("Pending explorer chunk is corrupt");
			}
			ExplorerSnapshotChunkCodec.decode(Files.readAllBytes(path), chunk.table());
		}
	}

	private boolean isCanonical(PendingCapture capture) {
		StoredBlock canonical = chainQuery.getStoredBlockByHeight(capture.height()).orElse(null);
		return canonical != null && canonical.getHash().equals(capture.hash())
				&& canonical.getBlock().getHeader().getStateRootHash().equals(capture.stateRoot())
				&& canonical.getBlock().getHeader().getTimestamp().equals(capture.blockTimestamp());
	}

	private boolean isMature(PendingCapture capture) {
		if (!isCanonical(capture) || capture.blockTimestamp().isBefore(Instant.EPOCH)) {
			return false;
		}
		StoredBlock head = chainQuery.getLatestStoredBlockOrThrow();
		if (head.getHeight() < capture.height() || head.getBlock() == null
				|| head.getBlock().getHeader() == null) {
			return false;
		}
		Instant headTimestamp = head.getBlock().getHeader().getTimestamp();
		if (headTimestamp == null || headTimestamp.isBefore(capture.blockTimestamp())) {
			return false;
		}
		try {
			return !headTimestamp.isBefore(capture.blockTimestamp().plus(MATURITY));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private ExplorerSnapshotBinding readCoreBinding(VerifiedCorePublication core) throws IOException {
		SnapshotTransportManifest stateEnvelope = objectMapper.readValue(
				core.directory().resolve("manifest.json").toFile(), SnapshotTransportManifest.class);
		CoreSnapshotArchiveTransportManifest archiveEnvelope = objectMapper.readValue(
				core.directory().resolve("archive-manifest.json").toFile(),
				CoreSnapshotArchiveTransportManifest.class);
		CheckpointSnapshotManifest state = stateEnvelope.decodeAndVerify();
		var archive = archiveEnvelope.decodeAndVerify();
		if (!archive.stateManifestSigningHash().toHexString().equals(stateEnvelope.manifestSigningHash())) {
			throw new ExplorerSnapshotException("Core archive is not bound to the generated state manifest");
		}
		return new ExplorerSnapshotBinding(
				state.networkCode(), state.chainIdentity().chainId(), state.chainIdentity().genesisHash(),
				state.checkpointHeight(), state.checkpointHash().toHexString(), state.checkpointStateRoot().toHexString(),
				stripPrefix(stateEnvelope.manifestSigningHash()), stripPrefix(archiveEnvelope.manifestSigningHash()));
	}

	private void materialize(PendingCapture capture, ExplorerSnapshotBinding core, Path output) throws IOException {
		Path bundle = capture.directory().resolve(BUNDLE_DIRECTORY);
		ExplorerSnapshotManifestCodec codec = new ExplorerSnapshotManifestCodec(objectMapper);
		ExplorerSnapshotManifest pending = codec.decode(
				Files.readAllBytes(bundle.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME)));
		ExplorerSnapshotManifest rebound = codec.sign(new ExplorerSnapshotManifest(
				SnapshotFormatCompatibility.CURRENT_EXPLORER_FORMAT,
				pending.carrierNetworkCode(), pending.chainId(), pending.genesisHash(),
				pending.checkpointHeight(), pending.checkpointHash(), pending.checkpointStateRoot(),
				core.coreStateSigningHash(), core.coreArchiveSigningHash(), pending.explorerMigrationFingerprint(),
				pending.tableSchemaVersions(), pending.tableRowCounts(), pending.chunks(), null));
		Files.createDirectory(output);
		try {
			for (ExplorerSnapshotChunkDescriptor chunk : rebound.chunks()) {
				Path source = safeChunk(bundle, chunk.fileName());
				Files.copy(source, output.resolve(chunk.fileName()));
			}
			Files.write(output.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME), codec.encode(rebound),
					CREATE_NEW);
			log.info("EXPLORER SNAPSHOT: Finalized snapshot bound to CORE height {}", core.checkpointHeight());
		} catch (Exception e) {
			deleteRecursively(output);
			throw e;
		}
	}

	private void prepareRoot() throws IOException {
		if (pendingRoot == null) {
			throw new ExplorerSnapshotException("Snapshot publication directory is unavailable");
		}
		Path current = pendingRoot.getRoot();
		for (Path component : pendingRoot) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new ExplorerSnapshotException("Explorer pending capture path contains a symbolic link");
			}
		}
		Files.createDirectories(pendingRoot);
		if (Files.isSymbolicLink(pendingRoot)
				|| !pendingRoot.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(pendingRoot)) {
			throw new ExplorerSnapshotException("Explorer pending capture directory is unsafe");
		}
	}

	private void cleanupTemporaryCaptures() throws IOException {
		try (var entries = Files.list(pendingRoot)) {
			for (Path path : entries.filter(value -> value.getFileName().toString().startsWith(".capture-")).toList()) {
				deleteRecursively(path);
			}
		}
	}

	private void writeMetadata(Path file, CaptureAnchor anchor, Instant capturedAt) throws IOException {
		String value = "format=1\nheight=" + anchor.height() + "\nhash=" + anchor.hash().toHexString()
				+ "\nstateRoot=" + anchor.stateRoot().toHexString()
				+ "\nblockTimestampMillis=" + anchor.blockTimestamp().toEpochMilli()
				+ "\ncapturedAtMillis=" + capturedAt.toEpochMilli() + "\n";
		try (FileChannel channel = FileChannel.open(file, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII));
			while (bytes.hasRemaining()) {
				channel.write(bytes);
			}
			channel.force(true);
		}
	}

	private Path safeChunk(Path bundle, String fileName) {
		Path path = bundle.resolve(fileName).normalize();
		if (!path.getParent().equals(bundle) || !regular(path)) {
			throw new ExplorerSnapshotException("Unsafe pending explorer chunk path");
		}
		return path;
	}

	private boolean regular(Path path) {
		return !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
	}

	private String sha256(Path path) throws IOException {
		try (var input = Files.newInputStream(path)) {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private String pendingName(CaptureAnchor anchor, Instant capturedAt) {
		return "pending-" + anchor.height() + "-" + stripPrefix(anchor.hash().toHexString())
				+ "-" + capturedAt.toEpochMilli();
	}

	private String stripPrefix(String value) {
		return value.startsWith("0x") ? value.substring(2) : value;
	}

	private void deleteRecursively(Path directory) {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// A later publisher pass retries cleanup before reading pending state.
		}
	}

	record CaptureAnchor(long height, Hash hash, Hash stateRoot, Instant blockTimestamp) {
	}

	private record PendingCapture(
			long height,
			Hash hash,
			Hash stateRoot,
			Instant blockTimestamp,
			Instant capturedAt,
			Path directory) {
	}

	@FunctionalInterface
	interface CoreBindingResolver {
		ExplorerSnapshotBinding resolve(VerifiedCorePublication publication) throws Exception;
	}

	@FunctionalInterface
	interface CaptureBackend {
		CaptureAnchor capture(Path bundle, StoredChainIdentity identity, int chunkBytes) throws Exception;
	}
}
