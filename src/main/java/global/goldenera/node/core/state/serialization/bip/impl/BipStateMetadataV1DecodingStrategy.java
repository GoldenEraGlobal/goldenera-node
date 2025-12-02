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
package global.goldenera.node.core.state.serialization.bip.impl;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadDecoder;
import global.goldenera.node.core.state.serialization.bip.BipStateMetadataDecodingStrategy;
import global.goldenera.node.shared.consensus.state.BipStateMetadata;
import global.goldenera.node.shared.consensus.state.impl.BipStateMetadataImpl;
import global.goldenera.node.shared.enums.state.BipStateMetadataVersion;
import global.goldenera.rlp.RLPInput;

public class BipStateMetadataV1DecodingStrategy implements BipStateMetadataDecodingStrategy {

	@Override
	public BipStateMetadata decode(RLPInput input) {
		// txVersion
		Integer txVersionCode = input.readOptionalIntScalar();
		TxVersion txVersion = txVersionCode != null ? TxVersion.fromCode(txVersionCode) : null;

		// txPayload
		Bytes txPayloadBytes = input.readOptionalRaw();
		TxPayload txPayload = TxPayloadDecoder.INSTANCE.decode(txPayloadBytes, txVersion);

		// derivedTokenAddress
		Bytes derivedTokenAddressBytes = input.readOptionalBytes();
		Address derivedTokenAddress = derivedTokenAddressBytes != null
				? Address.wrap(derivedTokenAddressBytes)
				: null;

		return BipStateMetadataImpl.builder()
				.version(BipStateMetadataVersion.V1)
				.txVersion(txVersion)
				.txPayload(txPayload)
				.derivedTokenAddress(derivedTokenAddress)
				.build();
	}

}
