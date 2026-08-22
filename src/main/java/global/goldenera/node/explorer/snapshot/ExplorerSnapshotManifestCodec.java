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

import java.io.IOException;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class ExplorerSnapshotManifestCodec {

	private final ObjectMapper mapper;

	public ExplorerSnapshotManifestCodec(ObjectMapper objectMapper) {
		this.mapper = objectMapper.copy()
				.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
				.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	}

	public byte[] encode(ExplorerSnapshotManifest manifest) {
		try {
			return mapper.writeValueAsBytes(normalize(manifest));
		} catch (IOException e) {
			throw new ExplorerSnapshotException("Cannot encode explorer snapshot manifest", e);
		}
	}

	public ExplorerSnapshotManifest decode(byte[] bytes) {
		try {
			return normalize(mapper.readValue(bytes, ExplorerSnapshotManifest.class));
		} catch (IOException | RuntimeException e) {
			throw new ExplorerSnapshotException("Cannot decode explorer snapshot manifest", e);
		}
	}

	public ExplorerSnapshotManifest sign(ExplorerSnapshotManifest unsigned) {
		ExplorerSnapshotManifest normalized = normalize(withSigningHash(unsigned, null));
		return withSigningHash(normalized, ExplorerSnapshotDigests.sha256(encode(normalized)));
	}

	public boolean hasValidSigningHash(ExplorerSnapshotManifest manifest) {
		if (manifest.signingHash() == null) {
			return false;
		}
		ExplorerSnapshotManifest unsigned = withSigningHash(normalize(manifest), null);
		return manifest.signingHash().equals(ExplorerSnapshotDigests.sha256(encode(unsigned)));
	}

	private static ExplorerSnapshotManifest normalize(ExplorerSnapshotManifest manifest) {
		return new ExplorerSnapshotManifest(
				manifest.formatVersion(), manifest.carrierNetworkCode(), manifest.chainId(), manifest.genesisHash(),
				manifest.checkpointHeight(), manifest.checkpointHash(), manifest.checkpointStateRoot(),
				manifest.coreStateSigningHash(), manifest.coreArchiveSigningHash(),
				manifest.explorerMigrationFingerprint(), new TreeMap<>(manifest.tableSchemaVersions()),
				new TreeMap<>(manifest.tableRowCounts()), List.copyOf(manifest.chunks()), manifest.signingHash());
	}

	private static ExplorerSnapshotManifest withSigningHash(ExplorerSnapshotManifest manifest, String signingHash) {
		return new ExplorerSnapshotManifest(
				manifest.formatVersion(), manifest.carrierNetworkCode(), manifest.chainId(), manifest.genesisHash(),
				manifest.checkpointHeight(), manifest.checkpointHash(), manifest.checkpointStateRoot(),
				manifest.coreStateSigningHash(), manifest.coreArchiveSigningHash(),
				manifest.explorerMigrationFingerprint(), manifest.tableSchemaVersions(), manifest.tableRowCounts(),
				manifest.chunks(), signingHash);
	}

}
