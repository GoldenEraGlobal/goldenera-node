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

import static global.goldenera.node.shared.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.enums.WebhookEventType;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GERuntimeException;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.ClaimedDelivery;
import global.goldenera.node.shared.services.webhook.dtos.WebhookEventDtoV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Service
@ConditionalOnProperty(name = { "ge.general.postgresql-enable", "ge.general.webhook-enable" }, havingValue = "true")
public class WebhookDispatchService implements UniversalWebhookEventSink {
	static final Duration ROUTE_INTERVAL = Duration.ofMillis(250);
	static final Duration DELIVERY_INTERVAL = Duration.ofSeconds(1);
	static final Duration LEASE_DURATION = Duration.ofMinutes(2);
	static final int ROUTE_LIMIT = 256;
	static final int MAX_ROUTE_BATCHES = 8;
	static final int CLAIM_LIMIT = 64;
	static final int MAX_IN_FLIGHT = 64;
	static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	public static final String DELIVERY_ID_HEADER = "X-Webhook-Delivery-Id";
	public static final String EVENT_ID_HEADER = "X-Webhook-Event-Id";
	public static final String ATTEMPT_HEADER = "X-Webhook-Attempt";

	private final OkHttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final TaskScheduler scheduler;
	private final DurableUniversalWebhookStore store;
	private final MeterRegistry registry;
	private final AESGCMComponent encryption;
	private final BlockchainTxMapper txMapper;
	private final BlockchainBlockHeaderMapper blockHeaderMapper;
	private final String workerId;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean routing = new AtomicBoolean();
	private final AtomicBoolean dispatching = new AtomicBoolean();
	private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

	@Autowired
	public WebhookDispatchService(
			@Qualifier("webhookOkHttpClient") OkHttpClient httpClient,
			ObjectMapper objectMapper,
			@Qualifier(CORE_WEBHOOK_SCHEDULER) TaskScheduler scheduler,
			DurableUniversalWebhookStore store,
			MeterRegistry registry,
			AESGCMComponent encryption,
			BlockchainTxMapper txMapper,
			BlockchainBlockHeaderMapper blockHeaderMapper) {
		this(httpClient, objectMapper, scheduler, store, registry, encryption, txMapper, blockHeaderMapper,
				"universal-webhook-" + UUID.randomUUID());
	}

	WebhookDispatchService(
			OkHttpClient httpClient,
			ObjectMapper objectMapper,
			TaskScheduler scheduler,
			DurableUniversalWebhookStore store,
			MeterRegistry registry,
			AESGCMComponent encryption,
			BlockchainTxMapper txMapper,
			BlockchainBlockHeaderMapper blockHeaderMapper,
			String workerId) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.scheduler = scheduler;
		this.store = store;
		this.registry = registry;
		this.encryption = encryption;
		this.txMapper = txMapper;
		this.blockHeaderMapper = blockHeaderMapper;
		this.workerId = workerId;
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		scheduler.scheduleWithFixedDelay(this::routePendingEvents, ROUTE_INTERVAL);
		scheduler.scheduleWithFixedDelay(this::dispatchPendingBatches, DELIVERY_INTERVAL);
	}

	@Override
	public void processNewBlockEvent(Block block, List<BlockEvent> events, WebhookType source) {
		processNewBlockEvent(block, events, source, false);
	}

	@Override
	public void processNewBlockEvent(
			Block block, List<BlockEvent> events, WebhookType source, boolean historicalCatchup) {
		processNewBlockEvent(block, events, source, null, null, null, null,
				historicalCatchup ? block.getHeight() : null);
	}

	void processNewBlockEvent(
			Block block, List<BlockEvent> events, WebhookType source,
			UUID originEventKey, UUID originEpoch, Integer originStream, Long originSequence,
			Long historicalBlockHeight) {
		requireUniversalSource(source);
		var payload = new WebhookEventDtoV1.NewBlockEvent(
				WebhookEventType.NEW_BLOCK, source, blockHeaderMapper.mapBlockWithEvents(block, events));
		UUID eventId = originEventKey == null
				? stableEventId(source, WebhookEventType.NEW_BLOCK, block.getHash())
				: stableEventId(originEventKey, "block");
		append(eventId, source,
				WebhookEventType.NEW_BLOCK, null, payload, null, null, null,
				block.getHeader().getTimestamp(), originEpoch, originStream, originSequence, historicalBlockHeight);
	}

	@Override
	public void processAddressActivityEvent(
			Block block, Tx tx, WebhookTxStatus status, Integer index, WebhookType source) {
		processAddressActivityEvent(block, tx, status, index, source, false);
	}

	@Override
	public void processAddressActivityEvent(
			Block block, Tx tx, WebhookTxStatus status, Integer index, WebhookType source,
			boolean historicalCatchup) {
		processAddressActivityEvent(block, tx, status, index, source, null, null, null, null,
				historicalCatchup && block != null ? block.getHeight() : null);
	}

	void processAddressActivityEvent(
			Block block, Tx tx, WebhookTxStatus status, Integer index, WebhookType source,
			UUID originEventKey, UUID originEpoch, Integer originStream, Long originSequence,
			Long historicalBlockHeight) {
		requireUniversalSource(source);
		var payload = new WebhookEventDtoV1.AddressActivityEvent(
				WebhookEventType.ADDRESS_ACTIVITY, source, txMapper.mapTx(block, tx, index), status);
		Hash blockHash = block == null ? null : block.getHash();
		UUID eventId = originEventKey == null
				? stableEventId(source, WebhookEventType.ADDRESS_ACTIVITY, status, tx.getHash(), blockHash)
				: stableEventId(originEventKey, "tx", status, tx.getHash());
		append(eventId, source,
				WebhookEventType.ADDRESS_ACTIVITY, status, payload, tx.getSender(), tx.getRecipient(),
				tx.getTokenAddress(), tx.getTimestamp(), originEpoch, originStream, originSequence,
				historicalBlockHeight);
	}

	@Override
	public void processReorgEvent(
			Long oldHeight, Hash oldHash, Long newHeight, Hash newHash, WebhookType source) {
		processReorgEvent(oldHeight, oldHash, newHeight, newHash, source, null, null, null, null);
	}

	void processReorgEvent(
			Long oldHeight, Hash oldHash, Long newHeight, Hash newHash, WebhookType source,
			UUID originEventKey, UUID originEpoch, Integer originStream, Long originSequence) {
		requireUniversalSource(source);
		var payload = new WebhookEventDtoV1.ReorgEvent(
				WebhookEventType.REORG, source, oldHeight, oldHash, newHeight, newHash);
		UUID eventId = originEventKey == null
				? stableEventId(source, WebhookEventType.REORG, oldHeight, oldHash, newHeight, newHash)
				: stableEventId(originEventKey, "reorg");
		append(eventId, source,
				WebhookEventType.REORG, null, payload, null, null, null, Instant.now(),
				originEpoch, originStream, originSequence, null);
	}

	private void append(
			UUID eventId,
			WebhookType source,
			WebhookEventType eventType,
			WebhookTxStatus status,
			WebhookEventDtoV1 payload,
			Address addressA,
			Address addressB,
			Address tokenAddress,
			Instant occurredAt,
			UUID originEpoch,
			Integer originStream,
			Long originSequence,
			Long originBlockHeight) {
		try {
			store.append(eventId, source, eventType, status, objectMapper.writeValueAsString(payload),
					addressA, addressB, tokenAddress, occurredAt == null ? Instant.now() : occurredAt,
					originEpoch, originStream, originSequence, originBlockHeight);
		} catch (JsonProcessingException exception) {
			throw new GERuntimeException("Failed to serialize durable webhook event", exception);
		}
	}

	void routePendingEvents() {
		if (!routing.compareAndSet(false, true)) {
			return;
		}
		try {
			for (int batch = 0; batch < MAX_ROUTE_BATCHES; batch++) {
				if (store.routePending(ROUTE_LIMIT, Instant.now()) == 0) {
					break;
				}
			}
		} catch (RuntimeException exception) {
			log.error("Durable webhook routing iteration failed", exception);
		} finally {
			routing.set(false);
		}
	}

	public void dispatchPendingBatches() {
		if (!dispatching.compareAndSet(false, true)) {
			return;
		}
		try {
			int available = Math.min(CLAIM_LIMIT, inFlight.availablePermits());
			if (available == 0) {
				return;
			}
			for (ClaimedDelivery delivery : store.claimAvailable(
					workerId, Instant.now(), LEASE_DURATION, available)) {
				inFlight.acquireUninterruptibly();
				deliver(delivery);
			}
		} catch (RuntimeException exception) {
			log.error("Durable webhook delivery iteration failed", exception);
		} finally {
			dispatching.set(false);
		}
	}

	private void deliver(ClaimedDelivery delivery) {
		Instant now = Instant.now();
		if (delivery.encryptedSecret() == null) {
			store.markDead(delivery.deliveryId(), workerId, null,
					"Webhook destination has no signing secret", now);
			inFlight.release();
			return;
		}
		try {
			JsonNode payload = objectMapper.readTree(delivery.payload());
			byte[] body = objectMapper.writeValueAsBytes(List.of(payload));
			Bytes secret = encryption.decrypt(Bytes.wrap(delivery.encryptedSecret()));
			String timestamp = String.valueOf(now.getEpochSecond());
			Request request = new Request.Builder()
					.url(delivery.url())
					.header("X-Webhook-Timestamp", timestamp)
					.header("X-Webhook-Signature", calculateSignature(secret, timestamp, body))
					.header(DELIVERY_ID_HEADER, delivery.deliveryId().toString())
					.header(EVENT_ID_HEADER, delivery.eventId().toString())
					.header(ATTEMPT_HEADER, String.valueOf(delivery.attempt()))
					.post(RequestBody.create(body, JSON))
					.build();
			execute(delivery, request);
		} catch (Exception exception) {
			store.markDead(delivery.deliveryId(), workerId, null,
					"Cannot prepare signed webhook request: " + exception.getMessage(), Instant.now());
			inFlight.release();
		}
	}

	private void execute(ClaimedDelivery delivery, Request request) {
		Timer.Sample sample = Timer.start(registry);
		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException exception) {
				try {
					sample.stop(registry.timer("webhook.delivery.latency", "status", "error"));
					complete(delivery, null, null, exception.getMessage(), true);
				} finally {
					inFlight.release();
				}
			}

			@Override
			public void onResponse(Call call, Response response) {
				try (response) {
					sample.stop(registry.timer("webhook.delivery.latency", "status", String.valueOf(response.code())));
					complete(delivery, response.code(), response.header("Retry-After"),
							"HTTP " + response.code(), false);
				} finally {
					inFlight.release();
				}
			}
		});
	}

	private void complete(
			ClaimedDelivery delivery, Integer httpStatus, String retryAfter, String error, boolean networkFailure) {
		Instant now = Instant.now();
		if (!networkFailure && httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
			store.markDelivered(delivery.deliveryId(), workerId, httpStatus, now);
			return;
		}
		boolean transientFailure = networkFailure || httpStatus == null || httpStatus == 408 || httpStatus == 429
				|| httpStatus >= 500;
		if (transientFailure) {
			store.markRetry(delivery.deliveryId(), workerId, httpStatus, error,
					retryAt(delivery.attempt(), retryAfter, now), now);
		} else {
			store.markDead(delivery.deliveryId(), workerId, httpStatus, error, now);
		}
	}

	private Duration retryDelay(int attempt) {
		long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 12);
		return Duration.ofSeconds(Math.min(3_600L, 5L * multiplier));
	}

	private Instant retryAt(int attempt, String retryAfter, Instant now) {
		if (retryAfter != null) {
			try {
				long seconds = Long.parseLong(retryAfter.trim());
				if (seconds >= 0) {
					return now.plusSeconds(Math.min(seconds, Duration.ofDays(1).toSeconds()));
				}
			} catch (NumberFormatException ignored) {
				// Fall back to capped exponential backoff.
			}
		}
		return now.plus(retryDelay(attempt));
	}

	private String calculateSignature(Bytes secretKey, String timestamp, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secretKey.toArray(), "HmacSHA256"));
			mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) '.');
			return Base64.getEncoder().encodeToString(mac.doFinal(body));
		} catch (NoSuchAlgorithmException | InvalidKeyException exception) {
			throw new GERuntimeException("Error creating HMAC signature", exception);
		}
	}

	static UUID stableEventId(Object... components) {
		StringBuilder value = new StringBuilder("goldenera:universal-webhook:v1");
		for (Object component : components) {
			value.append('|');
			if (component instanceof Bytes bytes) {
				value.append(bytes.toHexString());
			} else {
				value.append(component);
			}
		}
		return UUID.nameUUIDFromBytes(value.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void requireUniversalSource(WebhookType source) {
		if (source == WebhookType.BRIDGE) {
			throw new IllegalArgumentException("Bridge webhooks use their dedicated delivery pipeline");
		}
	}
}
