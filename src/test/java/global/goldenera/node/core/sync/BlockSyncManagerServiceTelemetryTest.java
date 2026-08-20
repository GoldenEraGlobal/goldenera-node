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
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;

class BlockSyncManagerServiceTelemetryTest {
	private static final Address PEER =
			Address.fromHexString("0x1111111111111111111111111111111111111111");

	@Test
	void retainsCumulativeHeadersFirstEvidenceAfterRequestsComplete() {
		var telemetry = new BlockSyncManagerService.SyncRequestTelemetry();

		telemetry.recordHeaderRequest();
		telemetry.recordHeaderRequest();
		telemetry.recordBodyRequest();
		telemetry.recordBodyRequest();

		var snapshot = telemetry.snapshot();
		assertThat(snapshot.headerRequestsIssued()).isEqualTo(2);
		assertThat(snapshot.bodyRequestsIssued()).isEqualTo(2);
		assertThat(snapshot.firstHeaderRequestSequence()).isEqualTo(1);
		assertThat(snapshot.firstBodyRequestSequence()).isEqualTo(3);
	}

	@Test
	void publishesEachCountAndItsFirstSequenceAsOneConsistentTuple() {
		var telemetry = new BlockSyncManagerService.SyncRequestTelemetry();

		telemetry.recordHeaderRequest();
		var afterHeader = telemetry.snapshot();
		telemetry.recordBodyRequest();
		var afterBody = telemetry.snapshot();

		assertThat(afterHeader.headerRequestsIssued()).isOne();
		assertThat(afterHeader.firstHeaderRequestSequence()).isOne();
		assertThat(afterHeader.bodyRequestsIssued()).isZero();
		assertThat(afterHeader.firstBodyRequestSequence()).isZero();
		assertThat(afterBody.bodyRequestsIssued()).isOne();
		assertThat(afterBody.firstBodyRequestSequence()).isEqualTo(2);
	}

	@Test
	void transientEmptyHeaderResponsesRequireRepeatedConfirmationBeforeBan() {
		var tracker = new BlockSyncManagerService.EmptyHeaderClaimTracker();
		Hash localHead = Hash.hash(Bytes.of(1));

		assertThat(tracker.record(PEER, localHead)).isFalse();
		assertThat(tracker.record(PEER, localHead)).isFalse();
		assertThat(tracker.record(PEER, localHead)).isTrue();
	}

	@Test
	void localProgressOrSuccessfulResponseResetsEmptyHeaderConfirmation() {
		var tracker = new BlockSyncManagerService.EmptyHeaderClaimTracker();
		Hash firstLocalHead = Hash.hash(Bytes.of(1));
		Hash advancedLocalHead = Hash.hash(Bytes.of(2));

		assertThat(tracker.record(PEER, firstLocalHead)).isFalse();
		assertThat(tracker.record(PEER, firstLocalHead)).isFalse();
		assertThat(tracker.record(PEER, advancedLocalHead)).isFalse();

		tracker.clear(PEER);
		assertThat(tracker.record(PEER, advancedLocalHead)).isFalse();
	}
}
