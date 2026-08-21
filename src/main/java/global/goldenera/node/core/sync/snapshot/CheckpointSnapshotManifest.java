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
package global.goldenera.node.core.sync.snapshot;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

public record CheckpointSnapshotManifest(
		int formatVersion,
		int networkCode,
		StoredChainIdentity chainIdentity,
		long checkpointHeight,
		Hash checkpointHash,
		Hash checkpointStateRoot,
		BigInteger checkpointCumulativeDifficulty,
		SnapshotHeaderSegment headerSegment,
		List<SnapshotChunkDescriptor> chunks) {

	public CheckpointSnapshotManifest {
		Objects.requireNonNull(chainIdentity, "chainIdentity");
		Objects.requireNonNull(checkpointHash, "checkpointHash");
		Objects.requireNonNull(checkpointStateRoot, "checkpointStateRoot");
		Objects.requireNonNull(checkpointCumulativeDifficulty, "checkpointCumulativeDifficulty");
		Objects.requireNonNull(headerSegment, "headerSegment");
		chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
	}
}
