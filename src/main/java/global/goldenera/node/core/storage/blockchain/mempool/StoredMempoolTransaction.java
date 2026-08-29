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
package global.goldenera.node.core.storage.blockchain.mempool;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;

public record StoredMempoolTransaction(
		int version,
		StoredMempoolStatus status,
		Hash txHash,
		byte[] rawSignedTx,
		Instant firstSeenTime,
		long firstSeenHeight,
		MempoolAdmissionReason admissionReason,
		Hash replacesTxHash) {

	public static final int CURRENT_VERSION = 1;
	public static final int MAX_RAW_TX_BYTES = 1024 * 1024;

	public StoredMempoolTransaction {
		if (version != CURRENT_VERSION) {
			throw new IllegalArgumentException("Unsupported persistent mempool record version " + version);
		}
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(txHash, "txHash");
		Objects.requireNonNull(firstSeenTime, "firstSeenTime");
		Objects.requireNonNull(admissionReason, "admissionReason");
		if (status != StoredMempoolStatus.ACTIVE || firstSeenHeight < -1L) {
			throw new IllegalArgumentException("Persistent mempool record metadata is invalid");
		}
		rawSignedTx = rawSignedTx == null ? new byte[0] : rawSignedTx.clone();
		if (rawSignedTx.length == 0 || rawSignedTx.length > MAX_RAW_TX_BYTES) {
			throw new IllegalArgumentException("Persistent mempool raw transaction size is invalid");
		}
		if (txHash.equals(replacesTxHash)) {
			throw new IllegalArgumentException("Persistent mempool replacement relation is self-referential");
		}
		Tx decoded;
		try {
			decoded = TxDecoder.INSTANCE.decode(Bytes.wrap(rawSignedTx));
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException("Persistent mempool transaction cannot be decoded", failure);
		}
		if (!txHash.equals(decoded.getHash()) || decoded.getSignature() == null) {
			throw new IllegalArgumentException("Persistent mempool transaction is unsigned or hash-mismatched");
		}
	}

	@Override
	public byte[] rawSignedTx() {
		return rawSignedTx.clone();
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof StoredMempoolTransaction that)) {
			return false;
		}
		return version == that.version
				&& firstSeenHeight == that.firstSeenHeight
				&& status == that.status
				&& txHash.equals(that.txHash)
				&& Arrays.equals(rawSignedTx, that.rawSignedTx)
				&& firstSeenTime.equals(that.firstSeenTime)
				&& admissionReason == that.admissionReason
				&& Objects.equals(replacesTxHash, that.replacesTxHash);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(
				version, status, txHash, firstSeenTime, firstSeenHeight, admissionReason, replacesTxHash);
		return 31 * result + Arrays.hashCode(rawSignedTx);
	}
}
