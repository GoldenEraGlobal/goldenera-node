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
package global.goldenera.node.core.blockchain.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.exceptions.CryptoJException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;

class TxValidatorIntegrationTest {

	private PrivateKey senderKey;
	private PrivateKey recipientKey;
	private TxValidator validator;

	@BeforeEach
	void setUp() {
		senderKey = PrivateKey.wrap(Bytes32.leftPad(Bytes32.fromHexString("0x01")));
		recipientKey = PrivateKey.wrap(Bytes32.leftPad(Bytes32.fromHexString("0x02")));
		GeneralProperties properties = new GeneralProperties();
		properties.setNetwork(Network.TESTNET);
		validator = new TxValidator(properties);
	}

	@Test
	void realSignedTransferPassesStatelessValidationAndRecoversSender() throws CryptoJException {
		Tx tx = signedTransfer(Network.TESTNET, recipientKey, 1L);

		assertThatCode(() -> validator.validateStateless(tx)).doesNotThrowAnyException();
		assertThat(tx.getSender()).isEqualTo(senderKey.getAddress());
	}

	@Test
	void realSignedTransferForAnotherNetworkIsRejected() throws CryptoJException {
		Tx tx = signedTransfer(Network.MAINNET, recipientKey, 1L);

		assertThatThrownBy(() -> validator.validateStateless(tx))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("network");
	}

	@Test
	void realSignedSelfTransferIsRejectedAfterSignatureRecovery() throws CryptoJException {
		Tx tx = signedTransfer(Network.TESTNET, senderKey, 1L);

		assertThatThrownBy(() -> validator.validateStateless(tx))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("Self-transfer");
	}

	private Tx signedTransfer(Network network, PrivateKey recipient, long nonce) throws CryptoJException {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(network)
				.recipient(recipient.getAddress())
				.amount(Wei.valueOf(BigInteger.valueOf(1_000)))
				.fee(Wei.valueOf(BigInteger.valueOf(100)))
				.nonce(nonce)
				.sign(senderKey);
	}
}
