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
package global.goldenera.node.bridge.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;

import global.goldenera.node.bridge.webhook.BridgeDeliveryStore.ClaimedDelivery;
import global.goldenera.node.shared.components.AESGCMComponent;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class BridgeDeliveryWorkerTest {

	private static final String OWNER = "test-worker";

	private final OkHttpClient httpClient = mock(OkHttpClient.class);
	private final Call call = mock(Call.class);
	private final BridgeDeliveryStore store = mock(BridgeDeliveryStore.class);
	private final AESGCMComponent encryption = mock(AESGCMComponent.class);
	private final BridgeWebhookSignatureService signatures = mock(BridgeWebhookSignatureService.class);
	private final BridgeDeliveryRetryPolicy retryPolicy = new BridgeDeliveryRetryPolicy();
	private final BridgeDeliveryWorker worker = new BridgeDeliveryWorker(
			httpClient,
			mock(TaskScheduler.class),
			store,
			encryption,
			signatures,
			retryPolicy,
			OWNER);

	@BeforeEach
	void setUp() {
		when(httpClient.newCall(any())).thenReturn(call);
		when(encryption.decrypt(any())).thenReturn(Bytes.wrap("secret".getBytes(StandardCharsets.UTF_8)));
		when(signatures.sign(any(), any(), any())).thenReturn("test-signature");
	}

	@Test
	void productionConstructorIsExplicitlySelectedForSpringInjection() {
		assertThat(BridgeDeliveryWorker.class.getConstructors())
				.filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
				.hasSize(1);
	}

	@Test
	void successfulDeliveryUsesStableIdentifiersAndMarksDelivered() throws Exception {
		ClaimedDelivery delivery = delivery(2);
		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		when(httpClient.newCall(requestCaptor.capture())).thenReturn(call);
		when(call.execute()).thenAnswer(invocation -> response(requestCaptor.getValue(), 204, null));

		worker.deliver(delivery);

		Request request = requestCaptor.getValue();
		assertThat(request.header(BridgeDeliveryWorker.DELIVERY_ID_HEADER))
				.isEqualTo(delivery.getDeliveryId().toString());
		assertThat(request.header(BridgeDeliveryWorker.EVENT_ID_HEADER)).isEqualTo(delivery.getEventId().toString());
		assertThat(request.header(BridgeDeliveryWorker.SEQUENCE_HEADER)).isEqualTo("42");
		assertThat(request.header(BridgeDeliveryWorker.ATTEMPT_HEADER)).isEqualTo("2");
		assertThat(request.header(BridgeDeliveryWorker.SIGNATURE_HEADER)).isEqualTo("test-signature");
		ArgumentCaptor<byte[]> signedBody = ArgumentCaptor.forClass(byte[].class);
		verify(signatures).sign(
				any(Bytes.class),
				eq(request.header(BridgeDeliveryWorker.TIMESTAMP_HEADER)),
				signedBody.capture());
		assertThat(new String(signedBody.getValue(), StandardCharsets.UTF_8)).isEqualTo(delivery.getBody());
		verify(store).markDelivered(eq(delivery.getDeliveryId()), eq(OWNER), eq(204), any(Instant.class));
	}

	@Test
	void rateLimitSchedulesRetryFromRetryAfter() throws Exception {
		ClaimedDelivery delivery = delivery(1);
		when(call.execute()).thenReturn(response(new Request.Builder().url(delivery.getUrl()).build(), 429, "60"));
		ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> completedAt = ArgumentCaptor.forClass(Instant.class);

		worker.deliver(delivery);

		verify(store).markRetry(
				eq(delivery.getDeliveryId()), eq(OWNER), eq(429), eq("HTTP 429"),
				retryAt.capture(), completedAt.capture());
		assertThat(Duration.between(completedAt.getValue(), retryAt.getValue())).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void networkFailureSchedulesRetry() throws Exception {
		ClaimedDelivery delivery = delivery(1);
		when(call.execute()).thenThrow(new IOException("connection reset"));

		worker.deliver(delivery);

		verify(store).markRetry(
				eq(delivery.getDeliveryId()), eq(OWNER), eq(null), eq("connection reset"),
				any(Instant.class), any(Instant.class));
	}

	@Test
	void permanentClientErrorIsDeadLettered() throws Exception {
		ClaimedDelivery delivery = delivery(1);
		when(call.execute()).thenReturn(response(new Request.Builder().url(delivery.getUrl()).build(), 400, null));

		worker.deliver(delivery);

		verify(store).markDead(eq(delivery.getDeliveryId()), eq(OWNER), eq(400), eq("HTTP 400"), any(Instant.class));
	}

	private ClaimedDelivery delivery(int attempt) {
		return new ClaimedDelivery(
				UUID.randomUUID(),
				UUID.randomUUID(),
				42L,
				attempt,
				"{\"eventId\":\"event\",\"sequence\":42}",
				"https://example.invalid/webhook",
				Bytes.wrap(new byte[] { 1 }));
	}

	private Response response(Request request, int status, String retryAfter) {
		Response.Builder builder = new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(status)
				.message("test");
		if (retryAfter != null) {
			builder.header("Retry-After", retryAfter);
		}
		return builder.build();
	}
}
