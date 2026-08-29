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
package global.goldenera.node.shared.services.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.ClaimedDelivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;

class WebhookDispatchServiceTest {

	@Test
	void productionConstructorIsExplicitlySelectedForSpring() {
		assertThat(WebhookDispatchService.class.getConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue());
	}
	@Test
	void preservesArrayContractAndRetriesTransientNetworkFailure() throws Exception {
		Fixture fixture = fixture();

		fixture.dispatch.dispatchPendingBatches();

		Request request = fixture.request.get();
		assertThat(request.header(WebhookDispatchService.DELIVERY_ID_HEADER))
				.isEqualTo(fixture.delivery.deliveryId().toString());
		assertThat(request.header(WebhookDispatchService.EVENT_ID_HEADER))
				.isEqualTo(fixture.delivery.eventId().toString());
		assertThat(request.header(WebhookDispatchService.ATTEMPT_HEADER)).isEqualTo("1");
		Buffer body = new Buffer();
		request.body().writeTo(body);
		assertThat(body.readUtf8()).isEqualTo("[{\"type\":\"NEW_BLOCK\"}]");

		fixture.callback.get().onFailure(fixture.call, new IOException("offline"));

		verify(fixture.store).markRetry(
				eq(fixture.delivery.deliveryId()), eq("worker"), eq(null), eq("offline"), any(), any());
		verify(fixture.store, never()).markDead(any(), any(), any(), any(), any());
	}

	@Test
	void permanentClientFailureBecomesDeadInsteadOfBeingSilentlyDropped() throws Exception {
		Fixture fixture = fixture();
		fixture.dispatch.dispatchPendingBatches();
		Response response = new Response.Builder()
				.request(fixture.request.get())
				.protocol(Protocol.HTTP_1_1)
				.code(400)
				.message("bad request")
				.build();

		fixture.callback.get().onResponse(fixture.call, response);

		verify(fixture.store).markDead(
				eq(fixture.delivery.deliveryId()), eq("worker"), eq(400), eq("HTTP 400"), any());
		verify(fixture.store, never()).markRetry(any(), any(), any(), any(), any(), any());
	}

	@Test
	void transientFailureKeepsRetryingAfterManyAttempts() throws Exception {
		Fixture fixture = fixture(99);
		fixture.dispatch.dispatchPendingBatches();

		fixture.callback.get().onFailure(fixture.call, new IOException("long outage"));

		verify(fixture.store).markRetry(
				eq(fixture.delivery.deliveryId()), eq("worker"), eq(null), eq("long outage"), any(), any());
		verify(fixture.store, never()).markDead(any(), any(), any(), any(), any());
	}

	private Fixture fixture() {
		return fixture(1);
	}

	private Fixture fixture(int attempt) {
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		ClaimedDelivery delivery = new ClaimedDelivery(
				UUID.randomUUID(), UUID.randomUUID(), 1L, attempt,
				"{\"type\":\"NEW_BLOCK\"}", "https://example.invalid/webhook", new byte[32]);
		when(store.claimAvailable(any(), any(), any(), anyInt())).thenReturn(List.of(delivery));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		when(encryption.decrypt(any(Bytes.class))).thenReturn(Bytes.wrap(new byte[32]));
		OkHttpClient client = mock(OkHttpClient.class);
		Call call = mock(Call.class);
		AtomicReference<Callback> callback = new AtomicReference<>();
		AtomicReference<Request> request = new AtomicReference<>();
		when(client.newCall(any())).thenAnswer(invocation -> {
			request.set(invocation.getArgument(0));
			return call;
		});
		doAnswer(invocation -> {
			callback.set(invocation.getArgument(0));
			return null;
		}).when(call).enqueue(any());
		WebhookDispatchService dispatch = new WebhookDispatchService(
				client, new ObjectMapper(), mock(TaskScheduler.class), store,
				new SimpleMeterRegistry(), encryption, mock(BlockchainTxMapper.class),
				mock(BlockchainBlockHeaderMapper.class), "worker");
		return new Fixture(dispatch, store, delivery, call, callback, request);
	}

	private record Fixture(
			WebhookDispatchService dispatch,
			DurableUniversalWebhookStore store,
			ClaimedDelivery delivery,
			Call call,
			AtomicReference<Callback> callback,
			AtomicReference<Request> request) {
	}
}
