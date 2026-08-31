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
package global.goldenera.node.core.p2p.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class DirectoryApiV1ClientTest {

	private static final MediaType JSON = MediaType.get("application/json");

	@Test
	void parsesStructuredUpgradeResponseWithoutMatchingItsMessage() {
		String responseBody = """
				{
				  "code": "NODE_VERSION_UNSUPPORTED",
				  "message": "This text may change.",
				  "network": "TESTNET",
				  "currentVersion": "0.1.0",
				  "minimumVersion": "0.1.1"
				}
				""";
		OkHttpClient httpClient = clientReturning(426, "Upgrade Required", responseBody);
		DirectoryApiV1Client client = new DirectoryApiV1Client(new ObjectMapper(), httpClient,
				"https://directory.test");

		assertThatThrownBy(() -> client.ping(new NodePingRequest()))
				.isInstanceOf(DirectoryNodeUpgradeRequiredException.class)
				.satisfies(error -> {
					DirectoryNodeUpgradeRequiredException upgrade =
							(DirectoryNodeUpgradeRequiredException) error;
					assertThat(upgrade.getMinimumVersion()).isEqualTo("0.1.1");
					assertThat(upgrade.getCurrentVersion()).isEqualTo("0.1.0");
				});
	}

	@Test
	void reportsNetworkTimeoutAsDirectoryUnavailable() {
		OkHttpClient httpClient = new OkHttpClient.Builder()
				.addInterceptor(chain -> {
					throw new SocketTimeoutException("connect timed out");
				})
				.build();
		DirectoryApiV1Client client = new DirectoryApiV1Client(new ObjectMapper(), httpClient,
				"https://directory.test");

		assertThatThrownBy(() -> client.ping(new NodePingRequest()))
				.isInstanceOf(DirectoryUnavailableException.class)
				.hasMessageContaining("https://directory.test")
				.hasMessageContaining("connect timed out")
				.hasRootCauseInstanceOf(SocketTimeoutException.class);
	}

	private OkHttpClient clientReturning(int code, String message, String body) {
		return new OkHttpClient.Builder()
				.addInterceptor(chain -> new Response.Builder()
						.request(chain.request())
						.protocol(Protocol.HTTP_1_1)
						.code(code)
						.message(message)
						.body(ResponseBody.create(body, JSON))
						.build())
				.build();
	}
}
