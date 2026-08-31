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

import static global.goldenera.node.shared.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;
import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.node.bridge.webhook.BridgeDeliveryRetryPolicy.Outcome;
import global.goldenera.node.bridge.webhook.BridgeDeliveryStore.ClaimedDelivery;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.services.webhook.AdaptivePollingLoop;
import jakarta.annotation.PreDestroy;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Service
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = { "ge.general.postgresql-enable", "ge.general.webhook-enable" }, havingValue = "true")
public class BridgeDeliveryWorker implements BridgeDeliveryWakeup {

	public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
	public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
	public static final String DELIVERY_ID_HEADER = "X-Webhook-Delivery-Id";
	public static final String EVENT_ID_HEADER = "X-Webhook-Event-Id";
	public static final String SEQUENCE_HEADER = "X-Webhook-Sequence";
	public static final String ATTEMPT_HEADER = "X-Webhook-Attempt";

	static final Duration MINIMUM_POLL_INTERVAL = Duration.ofSeconds(1);
	static final Duration RECOVERY_POLL_INTERVAL = Duration.ofSeconds(1);
	static final Duration LEASE_DURATION = Duration.ofMinutes(2);
	static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
	static final int CLAIM_LIMIT = 8;
	static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final AtomicInteger DELIVERY_THREAD_ID = new AtomicInteger();

	OkHttpClient httpClient;
	BridgeDeliveryStore store;
	AESGCMComponent encryption;
	BridgeWebhookSignatureService signatures;
	BridgeDeliveryRetryPolicy retryPolicy;
	String workerId;
	Executor deliveryExecutor;
	AtomicBoolean running = new AtomicBoolean();
	AtomicBoolean stopping = new AtomicBoolean();
	Semaphore deliveryPermits = new Semaphore(CLAIM_LIMIT);
	AdaptivePollingLoop pollingLoop;
	Object claimMonitor = new Object();
	Object completionFence = new Object();
	ConcurrentMap<DeliveryLease, Call> activeCalls = new ConcurrentHashMap<>();
	@NonFinal
	boolean leasesReleased;

	@Autowired
	public BridgeDeliveryWorker(
			@Qualifier("webhookOkHttpClient") OkHttpClient httpClient,
			@Qualifier(CORE_WEBHOOK_SCHEDULER) TaskScheduler scheduler,
			BridgeDeliveryStore store,
			AESGCMComponent encryption,
			BridgeWebhookSignatureService signatures,
			BridgeDeliveryRetryPolicy retryPolicy) {
		this(httpClient, scheduler, store, encryption, signatures, retryPolicy,
				"bridge-delivery-" + UUID.randomUUID(), newDeliveryExecutor());
	}

	BridgeDeliveryWorker(
			OkHttpClient httpClient,
			TaskScheduler scheduler,
			BridgeDeliveryStore store,
			AESGCMComponent encryption,
			BridgeWebhookSignatureService signatures,
			BridgeDeliveryRetryPolicy retryPolicy,
			String workerId) {
		this(httpClient, scheduler, store, encryption, signatures, retryPolicy, workerId, Runnable::run);
	}

	BridgeDeliveryWorker(
			OkHttpClient httpClient,
			TaskScheduler scheduler,
			BridgeDeliveryStore store,
			AESGCMComponent encryption,
			BridgeWebhookSignatureService signatures,
			BridgeDeliveryRetryPolicy retryPolicy,
			String workerId,
			Executor deliveryExecutor) {
		this.httpClient = httpClient;
		this.store = store;
		this.encryption = encryption;
		this.signatures = signatures;
		this.retryPolicy = retryPolicy;
		this.workerId = workerId;
		this.deliveryExecutor = deliveryExecutor;
		this.pollingLoop = new AdaptivePollingLoop(
				scheduler,
				command -> scheduler.schedule(command, Instant.now()),
				this::processAvailable,
				MINIMUM_POLL_INTERVAL,
				RECOVERY_POLL_INTERVAL);
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		pollingLoop.start();
	}

	@Override
	public void wake() {
		pollingLoop.wake();
	}

	@PreDestroy
	void stop() {
		if (!stopping.compareAndSet(false, true)) {
			return;
		}
		pollingLoop.stop();
		synchronized (claimMonitor) {
			// Wait until an active claim cycle has submitted all of its fenced work.
		}
		if (deliveryExecutor instanceof ExecutorService executorService) {
			executorService.shutdown();
			cancelActiveCalls();
			try {
				if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
					cancelActiveCalls();
					executorService.shutdownNow();
					executorService.awaitTermination(1, TimeUnit.SECONDS);
				}
			} catch (InterruptedException exception) {
				cancelActiveCalls();
				executorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		cancelActiveCalls();
		synchronized (completionFence) {
			try {
				store.releaseLeases(workerId, Instant.now());
			} finally {
				leasesReleased = true;
			}
		}
	}

	boolean processAvailable() {
		if (stopping.get()) {
			return false;
		}
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		boolean continueImmediately = false;
		try {
			synchronized (claimMonitor) {
				if (stopping.get()) {
					return false;
				}
				Instant now = Instant.now();
				int available = deliveryPermits.availablePermits();
				if (available == 0) {
					return false;
				}
				var claimed = store.claimAvailable(workerId, now, LEASE_DURATION, available);
				for (ClaimedDelivery delivery : claimed) {
					if (!deliveryPermits.tryAcquire()) {
						break;
					}
					try {
						deliveryExecutor.execute(() -> {
							try {
								if (!stopping.get()) {
									deliver(delivery);
								}
							} finally {
								deliveryPermits.release();
								wake();
							}
						});
					} catch (RuntimeException failure) {
						deliveryPermits.release();
						throw failure;
					}
				}
				continueImmediately = claimed.size() == available && deliveryPermits.availablePermits() > 0;
			}
		} catch (RuntimeException exception) {
			log.error("Bridge delivery worker iteration failed", exception);
			throw exception;
		} finally {
			running.set(false);
		}
		return continueImmediately;
	}

	void deliver(ClaimedDelivery delivery) {
		Instant now = Instant.now();
		if (delivery.getEncryptedSecret() == null) {
			markDead(delivery, null, "Webhook destination has no signing secret", now);
			return;
		}

		byte[] body = delivery.getBody().getBytes(StandardCharsets.UTF_8);
		try {
			Bytes secret = encryption.decrypt(delivery.getEncryptedSecret());
			String timestamp = String.valueOf(now.getEpochSecond());
			Request request = new Request.Builder()
					.url(delivery.getUrl())
					.header(TIMESTAMP_HEADER, timestamp)
					.header(SIGNATURE_HEADER, signatures.sign(secret, timestamp, body))
					.header(DELIVERY_ID_HEADER, delivery.getDeliveryId().toString())
					.header(EVENT_ID_HEADER, delivery.getEventId().toString())
					.header(SEQUENCE_HEADER, String.valueOf(delivery.getSequence()))
					.header(ATTEMPT_HEADER, String.valueOf(delivery.getAttempt()))
					.post(RequestBody.create(body, JSON))
					.build();
			execute(delivery, request);
		} catch (IllegalArgumentException exception) {
			markDead(delivery, null, exception.getMessage(), Instant.now());
		} catch (RuntimeException exception) {
			markDead(delivery, null,
					"Cannot prepare signed webhook request: " + exception.getMessage(), Instant.now());
		}
	}

	private void execute(ClaimedDelivery delivery, Request request) {
		Call call = httpClient.newCall(request);
		DeliveryLease lease = new DeliveryLease(delivery.getDeliveryId(), delivery.getAttempt());
		activeCalls.put(lease, call);
		if (stopping.get()) {
			call.cancel();
		}
		try (Response response = call.execute()) {
			complete(delivery, response.code(), response.header("Retry-After"),
					"HTTP " + response.code(), false);
		} catch (IOException exception) {
			complete(delivery, null, null, exception.getMessage(), true);
		} finally {
			activeCalls.remove(lease, call);
		}
	}

	private void complete(ClaimedDelivery delivery, Integer statusCode, String retryAfter,
			String error, boolean networkFailure) {
		synchronized (completionFence) {
			if (leasesReleased) {
				return;
			}
			Instant now = Instant.now();
			Outcome outcome = retryPolicy.classify(statusCode, networkFailure, delivery.getAttempt());
			switch (outcome) {
				case DELIVERED -> store.markDelivered(
						delivery.getDeliveryId(), workerId, delivery.getAttempt(), statusCode, now);
				case RETRY -> store.markRetry(
						delivery.getDeliveryId(),
						workerId,
						delivery.getAttempt(),
						statusCode,
						error,
						retryPolicy.nextAttemptAt(delivery.getAttempt(), statusCode, retryAfter, now),
						now);
				case DEAD -> store.markDead(
						delivery.getDeliveryId(), workerId, delivery.getAttempt(), statusCode, error, now);
			}
		}
	}

	private void markDead(ClaimedDelivery delivery, Integer statusCode, String error, Instant now) {
		synchronized (completionFence) {
			if (!leasesReleased) {
				store.markDead(
						delivery.getDeliveryId(), workerId, delivery.getAttempt(), statusCode, error, now);
			}
		}
	}

	private void cancelActiveCalls() {
		activeCalls.values().forEach(Call::cancel);
	}

	private record DeliveryLease(UUID deliveryId, int attempt) {
	}

	private static ExecutorService newDeliveryExecutor() {
		return new ThreadPoolExecutor(
				CLAIM_LIMIT,
				CLAIM_LIMIT,
				0L,
				TimeUnit.MILLISECONDS,
				new SynchronousQueue<>(),
				runnable -> {
			Thread thread = new Thread(runnable,
					"bridge-delivery-http-" + DELIVERY_THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}
}
