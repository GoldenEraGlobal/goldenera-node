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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.shared.properties.GeneralProperties;

class HttpExplorerSnapshotRemoteSourceTest {

	private static final ExplorerSnapshotBinding BINDING = new ExplorerSnapshotBinding(
			Network.TESTNET.getCode(), "testnet", "0x" + "11".repeat(32), 42,
			"0x" + "22".repeat(32), "0x" + "33".repeat(32), "44".repeat(32), "55".repeat(32));

	@TempDir
	Path temporaryDirectory;

	private HttpServer server;
	private URI source;
	private ObjectMapper objectMapper;
	private byte[] chunk;
	private AtomicInteger chunkRequests;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
		objectMapper = new ObjectMapper();
		chunk = ExplorerSnapshotChunkCodec.encodeHeader(
				ExplorerSnapshotTable.STATUS,
				List.of(new ExplorerSnapshotColumn("id", ExplorerSnapshotValueType.INT32, true)), 0);
		chunkRequests = new AtomicInteger();
		ExplorerSnapshotManifest manifest = manifest(BINDING);
		byte[] encoded = new ExplorerSnapshotManifestCodec(objectMapper).encode(manifest);
		server.createContext(HttpExplorerSnapshotRemoteSource.MANIFEST_PATH,
				exchange -> respond(exchange, 200, encoded));
		server.createContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH, exchange -> {
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
	void failsOverStagesBoundSnapshotAndDeletesOwnedDirectoryOnClose() throws Exception {
		SnapshotDistributionProperties properties = properties(List.of(
				URI.create("http://127.0.0.1:1/"), source));
		Path stagingBase = temporaryDirectory.resolve("staging");
		properties.setStagingDirectory(stagingBase);

		StagedExplorerSnapshotDownload staged = client(properties).stageFromFirstTrustedSource(BINDING);

		assertThat(Files.readAllBytes(staged.directory().resolve("explorer-status-0.bin"))).isEqualTo(chunk);
		assertThat(staged.manifest().signingHash()).isNotBlank();
		assertThat(chunkRequests).hasValue(ExplorerSnapshotTable.values().length);
		try (var children = Files.list(stagingBase)) {
			assertThat(children.toList()).containsExactly(staged.directory());
		}

		Path ownedDirectory = staged.directory();
		staged.close();
		assertThat(ownedDirectory).doesNotExist();
	}

	@Test
	void rejectsManifestBoundToDifferentCoreBeforeDownloadingChunks() {
		ExplorerSnapshotBinding differentBinding = new ExplorerSnapshotBinding(
				BINDING.carrierNetworkCode(), BINDING.chainId(), BINDING.genesisHash(), BINDING.checkpointHeight(),
				BINDING.checkpointHash(), BINDING.checkpointStateRoot(), "66".repeat(32),
				BINDING.coreArchiveSigningHash());

		assertThatThrownBy(() -> client(properties(List.of(source)))
				.stageFromFirstTrustedSource(differentBinding))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("All trusted explorer snapshot sources failed")
				.hasRootCauseMessage("Explorer snapshot is not bound to the activated core checkpoint");
		assertThat(chunkRequests).hasValue(0);
	}

	@Test
	void corruptChunkFailureRemovesOwnedStagingDirectory() throws Exception {
		server.removeContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH);
		byte[] corrupt = "corrupt-bytes".getBytes(StandardCharsets.UTF_8);
		server.createContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH,
				exchange -> respond(exchange, 200, corrupt));
		Path stagingBase = temporaryDirectory.resolve("corrupt-staging");
		SnapshotDistributionProperties properties = properties(List.of(source));
		properties.setStagingDirectory(stagingBase);

		assertThatThrownBy(() -> client(properties).stageFromFirstTrustedSource(BINDING))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasRootCauseMessage("Explorer snapshot chunk size/hash mismatch: explorer-block_header-0.bin");
		try (var children = Files.list(stagingBase)) {
			assertThat(children.toList()).isEmpty();
		}
	}

	@Test
	void retriesCompatibleTrustedSourceWhenFirstHasUnsupportedChunkFormat() throws Exception {
		server.removeContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH);
		byte[] unsupported = chunk.clone();
		unsupported[7] = 2;
		server.createContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH, exchange -> {
			chunkRequests.incrementAndGet();
			respond(exchange, 200, unsupported);
		});

		HttpServer fallback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		URI fallbackSource = URI.create("http://127.0.0.1:" + fallback.getAddress().getPort() + "/");
		AtomicInteger fallbackChunks = new AtomicInteger();
		byte[] manifestBytes = new ExplorerSnapshotManifestCodec(objectMapper).encode(manifest(BINDING));
		fallback.createContext(HttpExplorerSnapshotRemoteSource.MANIFEST_PATH,
				exchange -> respond(exchange, 200, manifestBytes));
		fallback.createContext(HttpExplorerSnapshotRemoteSource.CHUNKS_PATH, exchange -> {
			fallbackChunks.incrementAndGet();
			respond(exchange, 200, chunk);
		});
		fallback.start();
		try (StagedExplorerSnapshotDownload staged = client(properties(List.of(source, fallbackSource)))
				.stageFromFirstTrustedSource(BINDING)) {
			assertThat(staged.manifest().formatVersion()).isEqualTo(ExplorerSnapshotManifest.FORMAT_VERSION);
			assertThat(chunkRequests).hasValue(1);
			assertThat(fallbackChunks).hasValue(ExplorerSnapshotTable.values().length);
		} finally {
			fallback.stop(0);
		}
	}

	private ExplorerSnapshotManifest manifest(ExplorerSnapshotBinding binding) {
		List<ExplorerSnapshotChunkDescriptor> descriptors = new ArrayList<>();
		Map<String, Integer> schemaVersions = new HashMap<>();
		Map<String, Long> rowCounts = new HashMap<>();
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			long rowCount = table == ExplorerSnapshotTable.STATUS ? 1 : 0;
			descriptors.add(new ExplorerSnapshotChunkDescriptor(
					table, ExplorerSnapshotTable.SCHEMA_VERSION, 0, rowCount, chunk.length,
					ExplorerSnapshotDigests.sha256(chunk),
					"explorer-" + table.name().toLowerCase(Locale.ROOT) + "-0.bin"));
			schemaVersions.put(table.tableName(), ExplorerSnapshotTable.SCHEMA_VERSION);
			rowCounts.put(table.tableName(), rowCount);
		}
		ExplorerSnapshotManifest unsigned = new ExplorerSnapshotManifest(
				ExplorerSnapshotManifest.FORMAT_VERSION, binding.carrierNetworkCode(), binding.chainId(),
				binding.genesisHash(), binding.checkpointHeight(), binding.checkpointHash(),
				binding.checkpointStateRoot(), binding.coreStateSigningHash(), binding.coreArchiveSigningHash(),
				"77".repeat(32), schemaVersions, rowCounts, descriptors, null);
		return new ExplorerSnapshotManifestCodec(objectMapper).sign(unsigned);
	}

	private SnapshotDistributionProperties properties(List<URI> sources) {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setBootstrapEnabled(true);
		properties.setAllowHttpForTesting(true);
		properties.setTrustedSources(Map.of(Network.TESTNET, sources));
		return properties;
	}

	private HttpExplorerSnapshotRemoteSource client(SnapshotDistributionProperties properties) {
		GeneralProperties general = new GeneralProperties();
		general.setNetwork(Network.TESTNET);
		return new HttpExplorerSnapshotRemoteSource(objectMapper, properties, general);
	}

	private void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}
}
