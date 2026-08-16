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
package global.goldenera.node.shared.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import global.goldenera.node.shared.properties.ThrottlingProperties;

class ThrottlingServiceTest {

	@ParameterizedTest
	@CsvSource({
			"/api/bridge/v1/block/last?network=MAINNET, 3",
			"/api/bridge/v1/tx/by-hash/0x123?network=MAINNET, 5",
			"/api/bridge/v1/tx/broadcast, 10",
			"/api/bridge/v1/address/subscribe, 10",
			"/api/bridge/v1/address/subscription/123?network=MAINNET, 5",
			"/api/bridge/v1/address/0x123/nonce?network=MAINNET, 2"
	})
	void bridgeEndpointsConsumeConfiguredCost(String uri, long cost) {
		ThrottlingProperties properties = new ThrottlingProperties();
		properties.setApiKeyDefaultCapacity(cost);
		properties.setApiKeyDefaultRefillTokens(1);
		ThrottlingService service = new ThrottlingService(properties);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);

		assertThat(service.checkSpecificLimit(request, "bridge-key", true)).isTrue();
		assertThat(service.checkSpecificLimit(request, "bridge-key", true)).isFalse();
	}
}
