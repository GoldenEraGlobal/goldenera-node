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

import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.Constants;
import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import global.goldenera.node.core.p2p.directory.v1.NodePongResponse;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Component
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class DirectoryApiV1Client {

	private static final String DIRECTORY_PING_PATH = "/api/v1/node/ping";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	ObjectMapper objectMapper;
	OkHttpClient directoryOkHttpClient;

	public NodePongResponse ping(@NonNull NodePingRequest ping) {
		try {
			String jsonBody = objectMapper.writeValueAsString(ping);
			RequestBody body = RequestBody.create(jsonBody, JSON);
			Request request = new Request.Builder()
					.url(Constants.getDirectoryConfig().host() + DIRECTORY_PING_PATH)
					.post(body)
					.build();
			try (Response response = directoryOkHttpClient.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					log.warn("Directory PING request unsuccessful: {} (Code: {})",
							response.message(), response.code());
					throw new GEFailedException("Directory PONG response unsuccessful: " + response.message()
							+ " (Code: " + response.code() + ", Response: "
							+ (response.body() != null ? response.body().string() : "null") + ")");
				}
				if (response.body() != null) {
					String responseBody = response.body().string();
					log.debug("Directory PONG response successful");
					return objectMapper.readValue(responseBody, NodePongResponse.class);
				} else {
					log.warn("Directory PONG response body is empty.");
					throw new GEFailedException("Directory PONG response body is empty.");
				}
			}
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize or deserialize Directory PONG response: {}", e.getMessage());
			throw new GEFailedException(
					"Failed to serialize or deserialize Directory PONG response: " + e.getMessage());
		} catch (IOException e) {
			log.error("Directory PONG request failed for {}: {}", Constants.getDirectoryConfig().host(),
					e.getMessage());
			throw new GEFailedException(
					"Directory PONG request failed for " + Constants.getDirectoryConfig().host() + ": "
							+ e.getMessage());
		} catch (Exception e) {
			log.error("An unexpected error occurred: {}", e.getMessage());
			throw new GEFailedException("An unexpected error occurred: " + e.getMessage(), e);
		}
	}
}
