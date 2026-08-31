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
package global.goldenera.node.core.sync.snapshot.operator;

import java.nio.file.Path;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService.PublicationResult;
import global.goldenera.node.core.sync.snapshot.publication.CoreSnapshotPublicationGenerator;
import global.goldenera.node.core.sync.snapshot.publication.VerifiedCorePublication;

/** Automatic core-only generator backed by a consistent isolated RocksDB clone. */
public final class LiveHeadCoreSnapshotPublicationGenerator implements CoreSnapshotPublicationGenerator {

	private final LiveHeadCoreSnapshotCloneService cloneService;
	private final IsolatedLiveHeadSnapshotPublisher publisher;
	private final OfflineSnapshotOperatorProperties operatorProperties;

	public LiveHeadCoreSnapshotPublicationGenerator(
			LiveHeadCoreSnapshotCloneService cloneService,
			IsolatedLiveHeadSnapshotPublisher publisher,
			OfflineSnapshotOperatorProperties operatorProperties) {
		this.cloneService = cloneService;
		this.publisher = publisher;
		this.operatorProperties = operatorProperties;
	}

	@Override
	public VerifiedCorePublication generate(
			long snapshotHeight, Hash snapshotHash, Path outputDirectory) throws Exception {
		try (LiveHeadCoreSnapshotClone clone = cloneService.create(snapshotHeight, snapshotHash)) {
			PublicationResult result = publisher.publish(clone, operatorProperties, outputDirectory, false);
			if (result.verifiedArchive().checkpointHeight() != snapshotHeight
					|| !result.verifiedArchive().checkpointHash().equals(snapshotHash)) {
				throw new IllegalStateException("Verified clone publication does not match the requested lagged anchor");
			}
			return new VerifiedCorePublication(snapshotHeight, snapshotHash, outputDirectory);
		}
	}
}
