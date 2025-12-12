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
package global.goldenera.node.core.mempool.domain;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "tx", callSuper = false)
@AllArgsConstructor
public class MempoolEntry {

	@NonNull
	final Tx tx;
	@NonNull
	@Setter
	Instant firstSeenTime;
	@Setter
	long firstSeenHeight;
	@Setter
	Address receivedFrom;

	public MempoolEntry(Tx tx) {
		this.tx = tx;
		this.firstSeenTime = Instant.now();
		this.firstSeenHeight = -1L;
		this.receivedFrom = null;
	}

	public Long getNonce() {
		return tx.getNonce();
	}

	public Hash getHash() {
		return tx.getHash();
	}

	// Used only for sorting
	// Better performance than BigDecimal
	public double getFeeAsDouble() {
		return tx.getFee().toBigInteger().doubleValue();
	}

	public long getSizeInBytes() {
		return tx.getSize();
	}

}
