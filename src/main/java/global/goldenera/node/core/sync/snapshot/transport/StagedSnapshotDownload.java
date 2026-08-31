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

import java.nio.file.Path;
import java.util.List;

import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;

public record StagedSnapshotDownload(
		SnapshotTransportManifest manifest,
		CheckpointSnapshotManifest domainManifest,
		Path stagingDirectory,
		Path manifestFile,
		List<Path> chunkFiles) {

	public StagedSnapshotDownload {
		chunkFiles = List.copyOf(chunkFiles);
	}

	/** Provides the verifier with forward-only node streams over the staged files. */
	public SnapshotChunkSource chunkSource() {
		return descriptor -> {
			if (descriptor.index() < 0 || descriptor.index() >= chunkFiles.size()) {
				throw new IllegalArgumentException("No staged file for snapshot chunk " + descriptor.index());
			}
			return new BinarySnapshotNodeSource(chunkFiles.get(descriptor.index()), descriptor);
		};
	}
}
