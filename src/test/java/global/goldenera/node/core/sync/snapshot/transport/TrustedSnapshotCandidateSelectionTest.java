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

import java.net.URI;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.transport.HttpCheckpointSnapshotClient.TrustedSnapshotCandidate;

class TrustedSnapshotCandidateSelectionTest {

	@Test
	void selectsHighestArbitraryTrustedHeightAndSameAnchorFailoverOnly() {
		Hash lower = Hash.hash(Bytes.of(1));
		Hash highest = Hash.hash(Bytes.of(2));
		List<TrustedSnapshotCandidate> selected = HttpCheckpointSnapshotClient.selectHighestTrustedGroup(List.of(
				candidate("https://node-eu1.goldenera.global", 700_000, lower),
				candidate("https://node-eu2.goldenera.global", 777_777, highest),
				candidate("https://node-us1.goldenera.global", 777_777, highest)));

		assertThat(selected).extracting(TrustedSnapshotCandidate::source).containsExactly(
				URI.create("https://node-eu2.goldenera.global"),
				URI.create("https://node-us1.goldenera.global"));
	}

	@Test
	void neverDowngradesWhenHighestAnchorHasNoMatchingFailover() {
		Hash lower = Hash.hash(Bytes.of(1));
		Hash highest = Hash.hash(Bytes.of(2));

		List<TrustedSnapshotCandidate> selected = HttpCheckpointSnapshotClient.selectHighestTrustedGroup(List.of(
				candidate("https://lower-a.goldenera.global", 700_000, lower),
				candidate("https://highest.goldenera.global", 777_777, highest),
				candidate("https://lower-b.goldenera.global", 700_000, lower)));

		assertThat(selected).singleElement()
				.extracting(TrustedSnapshotCandidate::height)
				.isEqualTo(777_777L);
	}

	private TrustedSnapshotCandidate candidate(String source, long height, Hash hash) {
		return new TrustedSnapshotCandidate(URI.create(source), height, hash);
	}
}
