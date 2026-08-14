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
package global.goldenera.node.core.api.v1.blockchain.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayloadImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.node.core.api.v1.blockchain.dtos.TxPayloadDtoV1;

class MiningEconomicsPayloadMapperTest {

	private final TxMapper mapper = new TxMapper();

	@Test
	void mapsValidatorPolicyPayloadWithoutLosingVersionedFields() {
		Address validator = Address.fromHexString("0x1111111111111111111111111111111111111111");
		var payload = TxBipValidatorMiningPolicySetPayloadImpl.builder()
				.validatorAddress(validator)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(2_500)
				.build();

		TxPayloadDtoV1.ValidatorMiningPolicySet dto =
				(TxPayloadDtoV1.ValidatorMiningPolicySet) mapper.mapPayload(payload);

		assertThat(dto.getValidatorAddress()).isEqualTo(validator);
		assertThat(dto.getMiningLimitMode()).isEqualTo(MiningLimitMode.LIMITED);
		assertThat(dto.getMaxMiningShareBps()).isEqualTo(2_500);
	}

	@Test
	void mapsNetworkWindowResize() {
		var payload = TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.validatorMiningWindowBlocks(250L)
				.build();

		TxPayloadDtoV1.NetworkParamsSet dto =
				(TxPayloadDtoV1.NetworkParamsSet) mapper.mapPayload(payload);

		assertThat(dto.getValidatorMiningWindowBlocks()).isEqualTo(250);
	}
}
