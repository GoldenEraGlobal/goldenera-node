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
package global.goldenera.node.core.p2p.netty.protocol;

import static lombok.AccessLevel.PRIVATE;

import java.util.Arrays;

import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public enum P2PMessageType {
	// --- Handshake ---
	STATUS(0L), DISCONNECT(1L), PING(2L), PONG(3L),

	// --- Propagation (Push) ---
	NEW_BLOCK(20L), // Header + Txs

	// --- Sync ---
	GET_BLOCK_HEADERS(40L), //
	BLOCK_HEADERS(41L), //
	GET_BLOCK_BODIES(42L), //
	BLOCK_BODIES(43L),

	NEW_MEMPOOL_TX(60L), //
	GET_MEMPOOL_HASHES(61L), //
	MEMPOOL_HASHES(62L), //
	GET_MEMPOOL_TRANSACTIONS(63L), //
	MEMPOOL_TRANSACTIONS(64L); //

	long code;

	public static P2PMessageType fromCode(long code) {
		return Arrays.stream(values())
				.filter(t -> t.code == code)
				.findFirst()
				.orElseThrow(() -> new GEFailedException("Unknown P2P Message Type code: " + code));
	}
}