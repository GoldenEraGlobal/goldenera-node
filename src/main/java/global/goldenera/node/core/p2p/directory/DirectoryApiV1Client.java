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

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.Constants;
import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import global.goldenera.node.core.p2p.directory.v1.NodePongResponse;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.NonNull;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Component
public class DirectoryApiV1Client {

	private static final String DIRECTORY_PING_PATH = "/api/v1/node/ping";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final ObjectMapper objectMapper;
	private final OkHttpClient directoryOkHttpClient;
	private final String directoryHost;

	@Autowired
	public DirectoryApiV1Client(ObjectMapper objectMapper, OkHttpClient directoryOkHttpClient) {
		this(objectMapper, directoryOkHttpClient, Constants.getDirectoryConfig().host());
	}

	DirectoryApiV1Client(ObjectMapper objectMapper, OkHttpClient directoryOkHttpClient, String directoryHost) {
		this.objectMapper = objectMapper;
		this.directoryOkHttpClient = directoryOkHttpClient;
		this.directoryHost = directoryHost;
	}

	public NodePongResponse ping(@NonNull NodePingRequest ping) {
		String jsonBody;
		try {
			jsonBody = objectMapper.writeValueAsString(ping);
		} catch (JsonProcessingException e) {
			throw new GEFailedException("Failed to serialize Directory PING request", e);
		}

		Request request = new Request.Builder()
				.url(directoryHost + DIRECTORY_PING_PATH)
				.post(RequestBody.create(jsonBody, JSON))
				.build();
		try (Response response = directoryOkHttpClient.newCall(request).execute()) {
			String responseBody = response.body() == null ? "" : response.body().string();
			if (response.code() == 426) {
				throw parseUpgradeRequired(responseBody);
			}
			if (!response.isSuccessful()) {
				throw new GEFailedException("Directory PONG response unsuccessful: " + response.message()
						+ " (code: " + response.code() + ", response: " + responseBody + ")");
			}
			if (responseBody.isBlank()) {
				throw new GEFailedException("Directory PONG response body is empty");
			}
			try {
				return objectMapper.readValue(responseBody, NodePongResponse.class);
			} catch (JsonProcessingException e) {
				throw new GEFailedException("Failed to deserialize Directory PONG response", e);
			}
		} catch (IOException e) {
			throw new DirectoryUnavailableException(directoryHost, e);
		}
	}

	private DirectoryNodeUpgradeRequiredException parseUpgradeRequired(String responseBody) {
		try {
			DirectoryErrorResponse error = objectMapper.readValue(responseBody, DirectoryErrorResponse.class);
			if (!"NODE_VERSION_UNSUPPORTED".equals(error.code())) {
				throw new GEFailedException("Directory requested an upgrade with unknown error code: " + error.code());
			}
			return new DirectoryNodeUpgradeRequiredException(error.message(), error.currentVersion(),
					error.minimumVersion());
		} catch (JsonProcessingException e) {
			throw new GEFailedException("Directory returned an invalid upgrade-required response", e);
		}
	}
}
