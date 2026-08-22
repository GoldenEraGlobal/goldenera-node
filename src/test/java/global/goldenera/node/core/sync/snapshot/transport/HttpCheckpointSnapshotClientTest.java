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
package global.goldenera.node.core.sync.snapshot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.SnapshotHeaderSegment;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotChunkCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityType;
import global.goldenera.node.shared.properties.GeneralProperties;

class HttpCheckpointSnapshotClientTest {

	@TempDir
	Path temporaryDirectory;
	HttpServer server;
	URI source;
	byte[] chunk = new byte[] { 0, 0, 0, 0 }; // canonical empty chunk at index zero
	AtomicInteger chunkRequests;
	ObjectMapper objectMapper;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
		chunkRequests = new AtomicInteger();
		objectMapper = new ObjectMapper();
		server.createContext(HttpCheckpointSnapshotClient.CHUNKS_PATH + "0", exchange -> {
			chunkRequests.incrementAndGet();
			respond(exchange, 200, chunk);
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void streamsVerifiedChunkAndResumesOnlyFromVerifiedFinalFile() throws Exception {
		serveManifest(manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0));
		HttpCheckpointSnapshotClient client = client(properties(true));
		Path staging = temporaryDirectory.resolve("staging");

		StagedSnapshotDownload first = client.stage(source, staging);
		StagedSnapshotDownload resumed = client.stage(source, staging);

		assertThat(Files.readAllBytes(first.chunkFiles().getFirst())).isEqualTo(chunk);
		assertThat(first.domainManifest().checkpointHeight()).isEqualTo(700_000);
		try (var nodes = first.chunkSource().open(first.domainManifest().chunks().getFirst())) {
			assertThat(nodes.hasNext()).isFalse();
		}
		assertThat(resumed.chunkFiles()).containsExactly(first.chunkFiles().getFirst());
		assertThat(chunkRequests).hasValue(1);
		assertThat(Files.exists(staging.resolve("chunk-00000.bin.part"))).isFalse();
	}

	@Test
	void failsClosedAndRemovesPartialFileOnContentHashMismatch() throws Exception {
		serveManifest(manifest(null, Hash.ZERO.toHexString(), 0));
		Path staging = temporaryDirectory.resolve("mismatch");

		assertThatThrownBy(() -> client(properties(true)).stage(source, staging))
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("size/hash mismatch");
		assertThat(Files.exists(staging.resolve("chunk-00000.bin"))).isFalse();
		assertThat(Files.exists(staging.resolve("chunk-00000.bin.part"))).isFalse();
	}

	@Test
	void rejectsCrossOriginChunkBeforeMakingChunkRequest() throws Exception {
		serveManifest(manifest("http://127.0.0.1:1/evil", Hash.hash(Bytes.wrap(chunk)).toHexString(), 0));

		assertThatThrownBy(() -> client(properties(true)).stage(source, temporaryDirectory.resolve("origin")))
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("trusted manifest origin");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void rejectsManifestSigningHashMismatchBeforeMakingChunkRequest() throws Exception {
		SnapshotTransportManifest valid = manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0);
		serveManifest(new SnapshotTransportManifest(
				valid.canonicalManifest(), Hash.ZERO.toHexString(), valid.signature()));

		assertThatThrownBy(() -> client(properties(true)).stage(source, temporaryDirectory.resolve("signing-hash")))
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("signing hash mismatch");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void semanticManifestPreflightRunsBeforeAnyChunkRequest() throws Exception {
		serveManifest(manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0));
		SnapshotDistributionProperties properties = properties(true);
		GeneralProperties general = new GeneralProperties();
		general.setNetwork(Network.TESTNET);
		HttpCheckpointSnapshotClient client = new HttpCheckpointSnapshotClient(
				objectMapper, properties, general,
				ignored -> { throw new SnapshotTransportException("semantic preflight rejected"); });

		assertThatThrownBy(() -> client.stage(source, temporaryDirectory.resolve("preflight")))
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("semantic preflight rejected");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void rejectsUnsupportedStateFormatBeforeAnyChunkRequest() throws Exception {
		serveManifest(manifest(
				source, null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0, 0));

		assertThatThrownBy(() -> client(properties(true)).stageFullArchiveFromFirstTrustedSource())
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("All trusted full core snapshot manifests failed");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void plainHttpRequiresExplicitTestOnlyOptIn() {
		SnapshotDistributionProperties properties = properties(false);

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("HTTPS");
	}

	@Test
	void triesTrustedSourcesInOrderAndCleansFailedTemporaryStaging() throws Exception {
		serveManifest(manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0));
		SnapshotDistributionProperties properties = properties(true);
		properties.setTrustedSources(Map.of(Network.TESTNET, List.of(
				URI.create("http://127.0.0.1:1/"), source)));
		Path stagingBase = temporaryDirectory.resolve("fallback-staging");
		properties.setStagingDirectory(stagingBase);

		StagedSnapshotDownload download = client(properties).stageFromFirstTrustedSource();

		assertThat(Files.readAllBytes(download.chunkFiles().getFirst())).isEqualTo(chunk);
		try (var children = Files.list(stagingBase)) {
			assertThat(children.toList()).containsExactly(download.stagingDirectory());
		}
	}

	@Test
	void stagesFullArchiveFromSameOriginAndExposesStreamingBlockSource() throws Exception {
		SnapshotTransportManifest stateEnvelope = manifest(
				null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
		serveManifest(stateEnvelope);
		byte[] archiveChunk = archiveChunk(1, CoreSnapshotArchiveLimits.FORMAT_VERSION);
		byte[] entityChunk = entityChunk(CoreSnapshotEntityType.TOKEN, 0, 0, 1);
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION,
				CheckpointSnapshotManifestCodec.signingHash(stateEnvelope.decodeAndVerify()),
				List.of(new CoreSnapshotBlockChunkDescriptor(
						0, 0, 0, 1, CoreSnapshotChunkCompression.ZSTD,
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk)),
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk)))),
				List.of(new CoreSnapshotEntityChunkDescriptor(
						0, CoreSnapshotEntityType.TOKEN, 0,
						entityChunk.length, Hash.hash(Bytes.wrap(entityChunk)),
						CoreSnapshotEntityChunkCodec.HEADER_BYTES, Hash.ZERO)));
		serveArchive(archiveManifest, archiveChunk, entityChunk);

		try (StagedCoreSnapshotArchiveDownload staged = client(properties(true))
				.stageFullArchive(source, temporaryDirectory.resolve("full"));
				var input = staged.blockChunkSource().open(archiveManifest.blockChunks().getFirst())) {
			assertThat(input.readAllBytes()).isEqualTo(archiveChunk);
			try (var entityInput = staged.entityChunkSource().open(archiveManifest.entityChunks().getFirst())) {
				assertThat(entityInput.readAllBytes()).isEqualTo(entityChunk);
			}
			assertThat(staged.archiveManifest().stateManifestSigningHash())
					.isEqualTo(CheckpointSnapshotManifestCodec.signingHash(staged.stateSnapshot().domainManifest()));
		}
	}

	@Test
	void missingFullArchiveEndpointFailsInsteadOfReturningStateOnlyDownload() throws Exception {
		serveManifest(manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0));

		assertThatThrownBy(() -> client(properties(true))
				.stageFullArchive(source, temporaryDirectory.resolve("state-only")))
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("status 404");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void aggregateManifestFailurePreservesActionableHttpStatus() throws Exception {
		serveManifest(manifest(null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0));

		assertThatThrownBy(() -> client(properties(true)).stageFullArchiveFromFirstTrustedSource())
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("All trusted full core snapshot manifests failed")
				.hasMessageContaining("status 404")
				.hasCauseInstanceOf(SnapshotTransportException.class);
	}

	@Test
	void fullArchiveTrustedSourceFailoverCleansFailedStaging() throws Exception {
		SnapshotTransportManifest stateEnvelope = manifest(
				null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
		serveManifest(stateEnvelope);
		byte[] archiveChunk = archiveChunk(1, CoreSnapshotArchiveLimits.FORMAT_VERSION);
		serveArchive(new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION,
				CheckpointSnapshotManifestCodec.signingHash(stateEnvelope.decodeAndVerify()),
				List.of(new CoreSnapshotBlockChunkDescriptor(
						0, 0, 0, 1, CoreSnapshotChunkCompression.ZSTD,
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk)),
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk))))),
				archiveChunk);
		SnapshotDistributionProperties properties = properties(true);
		properties.setTrustedSources(Map.of(Network.TESTNET, List.of(
				URI.create("http://127.0.0.1:1/"), source)));
		Path stagingBase = temporaryDirectory.resolve("full-fallback");
		properties.setStagingDirectory(stagingBase);

		try (StagedCoreSnapshotArchiveDownload download =
				client(properties).stageFullArchiveFromFirstTrustedSource()) {
			try (var children = Files.list(stagingBase)) {
				assertThat(children.toList()).containsExactly(download.stateSnapshot().stagingDirectory());
			}
		}
	}

	@Test
	void resumesVerifiedChunksFromDeterministicCacheAcrossClientRestart() throws Exception {
		SnapshotTransportManifest stateEnvelope = manifest(
				null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
		serveManifest(stateEnvelope);
		byte[] archiveChunk = archiveChunk(1, CoreSnapshotArchiveLimits.FORMAT_VERSION);
		CoreSnapshotArchiveManifest archiveManifest = archiveManifest(stateEnvelope, 0, archiveChunk);
		byte[] archiveJson = objectMapper.writeValueAsBytes(
				CoreSnapshotArchiveTransportManifest.from(archiveManifest));
		server.createContext(HttpCheckpointSnapshotClient.ARCHIVE_MANIFEST_PATH,
				exchange -> respond(exchange, 200, archiveJson));
		AtomicInteger archiveRequests = new AtomicInteger();
		server.createContext(HttpCheckpointSnapshotClient.ARCHIVE_CHUNKS_PATH + "0", exchange -> {
			if (archiveRequests.incrementAndGet() == 1) {
				respond(exchange, 503, new byte[0]);
			} else {
				respond(exchange, 200, archiveChunk);
			}
		});
		SnapshotDistributionProperties properties = properties(true);
		Path cacheBase = temporaryDirectory.resolve("resume-cache");
		properties.setStagingDirectory(cacheBase);

		assertThatThrownBy(() -> client(properties).stageFullArchiveFromFirstTrustedSource())
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("checkpoint 0")
				.hasMessageContaining("status 503")
				.hasCauseInstanceOf(SnapshotTransportException.class);
		assertThat(chunkRequests).hasValue(1);
		assertThat(archiveRequests).hasValue(1);

		try (StagedCoreSnapshotArchiveDownload resumed =
				client(properties).stageFullArchiveFromFirstTrustedSource()) {
			assertThat(resumed.stateSnapshot().domainManifest().checkpointHeight()).isZero();
			assertThat(chunkRequests).hasValue(1);
			assertThat(archiveRequests).hasValue(2);
		}
		try (var children = Files.list(cacheBase)) {
			assertThat(children).isEmpty();
		}
	}

	@Test
	void aggregateStagingFailurePreservesActionableChunkHashMismatch() throws Exception {
		SnapshotTransportManifest stateEnvelope = manifest(null, Hash.ZERO.toHexString(), 0, 0);
		serveManifest(stateEnvelope);
		byte[] archiveChunk = archiveChunk(1, CoreSnapshotArchiveLimits.FORMAT_VERSION);
		serveArchive(archiveManifest(stateEnvelope, 0, archiveChunk), archiveChunk);

		assertThatThrownBy(() -> client(properties(true)).stageFullArchiveFromFirstTrustedSource())
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("checkpoint 0")
				.hasMessageContaining("size/hash mismatch")
				.hasCauseInstanceOf(SnapshotTransportException.class);
	}

	@Test
	void aggregateStagingFailurePreservesActionableChunkFormatMismatch() throws Exception {
		SnapshotTransportManifest stateEnvelope = manifest(
				null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
		serveManifest(stateEnvelope);
		byte[] archiveChunk = archiveChunk(1, 1);
		serveArchive(archiveManifest(stateEnvelope, 0, archiveChunk), archiveChunk);

		assertThatThrownBy(() -> client(properties(true)).stageFullArchiveFromFirstTrustedSource())
				.isInstanceOf(SnapshotTransportException.class)
				.hasMessageContaining("checkpoint 0")
				.hasMessageContaining("chunk format is incompatible")
				.hasCauseInstanceOf(SnapshotTransportException.class);
	}

	@Test
	void retriesCompatibleTrustedSourceWhenFirstHasUnsupportedBlockChunkFormat() throws Exception {
		SnapshotTransportManifest lowState = manifest(
				null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
		serveManifest(lowState);
		byte[] lowArchiveChunk = archiveChunk(1, 1);
		serveArchive(archiveManifest(lowState, 0, lowArchiveChunk), lowArchiveChunk);

		HttpServer higherServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		URI higherSource = URI.create("http://127.0.0.1:" + higherServer.getAddress().getPort() + "/");
		AtomicInteger higherStateRequests = new AtomicInteger();
		try {
			SnapshotTransportManifest highState = manifest(
					higherSource, null, Hash.hash(Bytes.wrap(chunk)).toHexString(), 0, 0);
			byte[] highArchiveChunk = archiveChunk(1, CoreSnapshotArchiveLimits.FORMAT_VERSION);
			CoreSnapshotArchiveManifest highArchive = archiveManifest(highState, 0, highArchiveChunk);
			higherServer.createContext(HttpCheckpointSnapshotClient.MANIFEST_PATH,
					exchange -> respond(exchange, 200, objectMapper.writeValueAsBytes(highState)));
			higherServer.createContext(HttpCheckpointSnapshotClient.ARCHIVE_MANIFEST_PATH,
					exchange -> respond(exchange, 200, objectMapper.writeValueAsBytes(
						CoreSnapshotArchiveTransportManifest.from(highArchive))));
			higherServer.createContext(HttpCheckpointSnapshotClient.CHUNKS_PATH + "0", exchange -> {
				higherStateRequests.incrementAndGet();
				respond(exchange, 200, chunk);
			});
			higherServer.createContext(HttpCheckpointSnapshotClient.ARCHIVE_CHUNKS_PATH + "0",
					exchange -> respond(exchange, 200, highArchiveChunk));
			higherServer.start();

			SnapshotDistributionProperties properties = properties(true);
			properties.setTrustedSources(Map.of(Network.TESTNET, List.of(source, higherSource)));
			try (StagedCoreSnapshotArchiveDownload staged =
					client(properties).stageFullArchiveFromFirstTrustedSource()) {
				assertThat(staged.stateSnapshot().domainManifest().checkpointHeight()).isZero();
				assertThat(chunkRequests).hasValue(1);
				assertThat(higherStateRequests).hasValue(1);
			}
		} finally {
			higherServer.stop(0);
		}
	}

	private void serveManifest(SnapshotTransportManifest manifest) throws Exception {
		byte[] json = objectMapper.writeValueAsBytes(manifest);
		server.createContext(HttpCheckpointSnapshotClient.MANIFEST_PATH,
				exchange -> respond(exchange, 200, json));
	}

	private SnapshotTransportManifest manifest(String url, String hash, long byteCount) {
		return manifest(url, hash, byteCount, 700_000);
	}

	private SnapshotTransportManifest manifest(String url, String hash, long byteCount, long checkpointHeight) {
		return manifest(source, url, hash, byteCount, checkpointHeight);
	}

	private SnapshotTransportManifest manifest(
			URI publicSource, String url, String hash, long byteCount, long checkpointHeight) {
		return manifest(publicSource, url, hash, byteCount, checkpointHeight, 1);
	}

	private SnapshotTransportManifest manifest(
			URI publicSource, String url, String hash, long byteCount, long checkpointHeight, int formatVersion) {
		String chunkUrl = url == null
				? publicSource.resolve(HttpCheckpointSnapshotClient.CHUNKS_PATH + "0").toString() : url;
		CheckpointSnapshotManifest manifest = new CheckpointSnapshotManifest(
				formatVersion,
				Network.TESTNET.getCode(),
				new StoredChainIdentity(1, Network.TESTNET.getCode(), "testnet", Hash.ZERO.toHexString(), null),
				checkpointHeight,
				Hash.ZERO,
				Hash.ZERO,
				BigInteger.ONE,
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, List.of()),
				List.of(new SnapshotChunkDescriptor(
						0, "chunk-0", chunkUrl, 0, byteCount, Hash.fromHexString(hash))));
		return SnapshotTransportManifest.from(manifest);
	}

	private CoreSnapshotArchiveManifest archiveManifest(
			SnapshotTransportManifest stateEnvelope,
			long checkpointHeight,
			byte[] archiveChunk) {
		return new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION,
				CheckpointSnapshotManifestCodec.signingHash(stateEnvelope.decodeAndVerify()),
				List.of(new CoreSnapshotBlockChunkDescriptor(
						0, 0, checkpointHeight, Math.toIntExact(checkpointHeight + 1),
						CoreSnapshotChunkCompression.ZSTD,
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk)),
						archiveChunk.length, Hash.hash(Bytes.wrap(archiveChunk)))));
	}

	private void serveArchive(CoreSnapshotArchiveManifest manifest, byte[] archiveChunk) throws Exception {
		serveArchive(manifest, archiveChunk, null);
	}

	private void serveArchive(
			CoreSnapshotArchiveManifest manifest, byte[] archiveChunk, byte[] entityChunk) throws Exception {
		byte[] json = objectMapper.writeValueAsBytes(CoreSnapshotArchiveTransportManifest.from(manifest));
		server.createContext(HttpCheckpointSnapshotClient.ARCHIVE_MANIFEST_PATH,
				exchange -> respond(exchange, 200, json));
		server.createContext(HttpCheckpointSnapshotClient.ARCHIVE_CHUNKS_PATH + "0",
				exchange -> respond(exchange, 200, archiveChunk));
		if (entityChunk != null) {
			server.createContext(HttpCheckpointSnapshotClient.ARCHIVE_ENTITY_CHUNKS_PATH + "0",
					exchange -> respond(exchange, 200, entityChunk));
		}
	}

	private byte[] archiveChunk(int blockCount, int version) throws IOException {
		ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(uncompressed)) {
			output.writeInt(0x47454341);
			output.writeInt(version);
			output.writeInt(0);
			output.writeInt(blockCount);
		}
		return compress(uncompressed.toByteArray());
	}

	private byte[] entityChunk(
			CoreSnapshotEntityType type, int index, int entryCount, int version) throws IOException {
		ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(uncompressed)) {
			output.writeInt(0x47454549);
			output.writeInt(version);
			output.writeInt(index);
			output.writeInt((type.code() << 24) | entryCount);
		}
		return compress(uncompressed.toByteArray());
	}

	private byte[] compress(byte[] uncompressed) throws IOException {
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		CoreSnapshotCompression.writeZstd(new ByteArrayInputStream(uncompressed), compressed);
		return compressed.toByteArray();
	}

	private SnapshotDistributionProperties properties(boolean allowHttp) {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setBootstrapEnabled(true);
		properties.setAllowHttpForTesting(allowHttp);
		properties.setTrustedSources(Map.of(Network.TESTNET, List.of(source)));
		properties.setParallelism(2);
		properties.setMaxManifestBytes(64 * 1024);
		properties.setMaxChunkBytes(1024 * 1024);
		properties.setMaxTotalBytes(2 * 1024 * 1024);
		return properties;
	}

	private HttpCheckpointSnapshotClient client(SnapshotDistributionProperties properties) {
		GeneralProperties general = new GeneralProperties();
		general.setNetwork(Network.TESTNET);
		return new HttpCheckpointSnapshotClient(objectMapper, properties, general);
	}

	private void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}
}
