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
package global.goldenera.node.core.mempool;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;

public final class MempoolTestFixtures {

	public static final Address ALICE = address(1);
	public static final Address BOB = address(2);
	public static final Address CAROL = address(3);

	private MempoolTestFixtures() {
	}

	public static MempoolProperties properties(long maxSize) {
		MempoolProperties properties = new MempoolProperties();
		properties.setMaxSize(maxSize);
		properties.setMaxNonceGap(100L);
		properties.setMinAcceptableFeeWei(BigInteger.ZERO);
		properties.setTxExpireTimeInMinutes(60);
		return properties;
	}

	public static MempoolEntry transfer(int id, Address sender, long nonce, long fee) {
		return entry(id, sender, nonce, fee, TxType.TRANSFER, null, null);
	}

	public static MempoolEntry governance(int id, Address sender, long nonce, long fee, TxPayload payload) {
		return entry(id, sender, nonce, fee, TxType.BIP_CREATE, payload, null);
	}

	public static MempoolEntry vote(int id, Address sender, long nonce, long fee, Hash bipHash) {
		return entry(id, sender, nonce, fee, TxType.BIP_VOTE, null, bipHash);
	}

	public static MempoolEntry entry(int id, Address sender, long nonce, long fee, TxType type,
			TxPayload payload, Hash referenceHash) {
		Tx tx = mock(Tx.class);
		when(tx.getHash()).thenReturn(hash(id));
		when(tx.getSender()).thenReturn(sender);
		when(tx.getNonce()).thenReturn(nonce);
		when(tx.getFee()).thenReturn(Wei.valueOf(fee));
		when(tx.getSize()).thenReturn(100);
		when(tx.getType()).thenReturn(type);
		when(tx.getPayload()).thenReturn(payload);
		when(tx.getReferenceHash()).thenReturn(referenceHash);
		if (type == TxType.TRANSFER) {
			when(tx.getTokenAddress()).thenReturn(Address.NATIVE_TOKEN);
			when(tx.getAmount()).thenReturn(Wei.ZERO);
		}
		return new MempoolEntry(tx);
	}

	public static Address address(int id) {
		return Address.fromHexString(String.format("0x%040x", id));
	}

	public static Hash hash(int id) {
		return Hash.fromHexString(String.format("0x%064x", id));
	}
}
