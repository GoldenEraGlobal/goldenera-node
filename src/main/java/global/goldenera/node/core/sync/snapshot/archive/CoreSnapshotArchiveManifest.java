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
package global.goldenera.node.core.sync.snapshot.archive;

import java.util.List;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;

/**
 * FULL CORE archive manifest. The state manifest signing hash binds the archive
 * to the independently verified checkpoint state; block descriptors bind the
 * complete canonical StoredBlock history from genesis through that checkpoint.
 */
public record CoreSnapshotArchiveManifest(
		int formatVersion,
		Hash stateManifestSigningHash,
		List<CoreSnapshotBlockChunkDescriptor> blockChunks,
		List<CoreSnapshotEntityChunkDescriptor> entityChunks) {

	public CoreSnapshotArchiveManifest {
		Objects.requireNonNull(stateManifestSigningHash, "stateManifestSigningHash");
		blockChunks = List.copyOf(Objects.requireNonNull(blockChunks, "blockChunks"));
		entityChunks = List.copyOf(Objects.requireNonNull(entityChunks, "entityChunks"));
	}

	public CoreSnapshotArchiveManifest(
			int formatVersion,
			Hash stateManifestSigningHash,
			List<CoreSnapshotBlockChunkDescriptor> blockChunks) {
		this(formatVersion, stateManifestSigningHash, blockChunks, List.of());
	}
}
