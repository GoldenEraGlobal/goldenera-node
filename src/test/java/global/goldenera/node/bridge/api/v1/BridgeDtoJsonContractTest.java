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
package global.goldenera.node.bridge.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeAddressNonceDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeLastBlockDtoV1;
import global.goldenera.node.shared.config.JacksonConfig;

class BridgeDtoJsonContractTest {

    private final ObjectMapper objectMapper = new JacksonConfig()
            .baseObjectMapper(new Jackson2ObjectMapperBuilder());

    @Test
    void serializesLastBlockWithXlibsScalarConventions() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(
                new BridgeLastBlockDtoV1(Network.MAINNET, 123L, BigInteger.valueOf(100_000_000L))));

        assertThat(json.get("network").asText()).isEqualTo("MAINNET");
        assertThat(json.get("blockNumber").asLong()).isEqualTo(123L);
        assertThat(json.get("fee").isTextual()).isTrue();
        assertThat(json.get("fee").asText()).isEqualTo("100000000");
    }

    @Test
    void serializesNonceAsDecimalString() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(
                new BridgeAddressNonceDtoV1(Network.TESTNET, "0xabc", BigInteger.ZERO)));

        assertThat(json.get("network").asText()).isEqualTo("TESTNET");
        assertThat(json.get("address").asText()).isEqualTo("0xabc");
        assertThat(json.get("nonce").isTextual()).isTrue();
        assertThat(json.get("nonce").asText()).isEqualTo("0");
    }
}
