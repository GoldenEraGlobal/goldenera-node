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
package global.goldenera.node.core.storage.blockchain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;

class LifecycleJournalCodecTest {

	@Test
	void roundTripsVersionedEntryWithoutSharingPayloadArray() {
		byte[] payload = new byte[] { 1, 2, 3 };
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				UUID.fromString("99999999-8888-7777-6666-555555555555"),
				42L,
				UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
				LifecycleJournalStream.CANONICAL,
				LifecycleJournalOperation.CONNECT,
				UUID.fromString("11111111-2222-3333-4444-555555555555"),
				2,
				4,
				123L,
				hash('1'),
				hash('2'),
				Instant.parse("2026-08-29T10:15:30.123456789Z"),
				3,
				-1,
				payload);
		payload[0] = 99;

		LifecycleJournalEntry decoded = new LifecycleJournalCodec().decode(
				new LifecycleJournalCodec().encode(entry));

		assertThat(decoded).usingRecursiveComparison().isEqualTo(entry);
		assertThat(decoded.payload()).containsExactly(1, 2, 3);
	}

	@Test
	void rejectsTruncationAndTrailingBytes() {
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, UUID.randomUUID(), 1L, UUID.randomUUID(), LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.PENDING, null, 0, 1, -1L,
				hash('3'), null, Instant.EPOCH, -1, MempoolLifecycleReason.NEW.code(), new byte[0]);
		byte[] encoded = new LifecycleJournalCodec().encode(entry);
		byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);

		assertThatThrownBy(() -> new LifecycleJournalCodec().decode(
				java.util.Arrays.copyOf(encoded, encoded.length - 1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LifecycleJournalCodec().decode(trailing))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Hash hash(char digit) {
		return Hash.fromHexString("0x" + String.valueOf(digit).repeat(64));
	}
}
