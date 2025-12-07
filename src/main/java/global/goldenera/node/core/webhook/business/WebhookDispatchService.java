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
package global.goldenera.node.core.webhook.business;

import static global.goldenera.node.core.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;
import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.blockchain.events.CoreReadyEvent;
import global.goldenera.node.core.enums.WebhookEventType;
import global.goldenera.node.core.enums.WebhookTxStatus;
import global.goldenera.node.core.webhook.business.dtos.WebhookEventDtoV1;
import global.goldenera.node.core.webhook.core.WebhookCoreService;
import global.goldenera.node.core.webhook.core.WebhookCoreService.WebhookEventFilter;
import global.goldenera.node.core.webhook.entities.Webhook;
import global.goldenera.node.core.webhook.entities.WebhookEvent;
import global.goldenera.node.core.webhook.events.WebhookEventsUpdateEvent;
import global.goldenera.node.core.webhook.events.WebhookUpdateEvent;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.events.ApiKeyUpdatedEvent;
import global.goldenera.node.shared.exceptions.GERuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;
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
@FieldDefaults(level = PRIVATE)
public class WebhookDispatchService {

	private static final int DEFAULT_DTO_VERSION = 1;

	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final int MAX_QUEUE_SIZE_PER_WEBHOOK = 10_000;
	private static final int MAX_BATCH_SIZE = 2000;

	final OkHttpClient okHttpClient;
	final ObjectMapper objectMapper;
	final TaskScheduler explorerScheduler;
	final WebhookCoreService webhookCoreService;
	final MeterRegistry registry;
	final AESGCMComponent aesGCMComponent;

	final BlockchainTxMapper blockchainTxMapper;
	final BlockchainBlockHeaderMapper blockchainBlockHeaderMapper;

	final Map<UUID, WebhookConfig> webhookConfigs = new ConcurrentHashMap<>();
	final Map<Long, Set<UUID>> apiKeyToWebhookIds = new ConcurrentHashMap<>();
	final Map<Address, Set<WebhookSubscription>> addressSubscriptions = new ConcurrentHashMap<>();
	final Set<WebhookSubscription> newBlockSubscriptions = ConcurrentHashMap.newKeySet();
	final Map<UUID, java.util.Queue<Object>> pendingBatches = new ConcurrentHashMap<>();

	public WebhookDispatchService(@Qualifier("webhookOkHttpClient") OkHttpClient webhookOkHttpClient,
			ObjectMapper objectMapper,
			@Qualifier(CORE_WEBHOOK_SCHEDULER) TaskScheduler explorerScheduler,
			WebhookCoreService webhookCoreService, MeterRegistry registry, AESGCMComponent aesGCMComponent,
			BlockchainTxMapper blockchainTxMapper, BlockchainBlockHeaderMapper blockchainBlockHeaderMapper) {
		this.okHttpClient = webhookOkHttpClient;
		this.objectMapper = objectMapper;
		this.explorerScheduler = explorerScheduler;
		this.webhookCoreService = webhookCoreService;
		this.registry = registry;
		this.aesGCMComponent = aesGCMComponent;
		this.blockchainTxMapper = blockchainTxMapper;
		this.blockchainBlockHeaderMapper = blockchainBlockHeaderMapper;
	}

	@EventListener(CoreReadyEvent.class)
	public void loadIndexOnStartup() {
		log.info("Core is ready. Loading all webhook filters...");
		loadAllFiltersIntoIndex();
	}

	@PostConstruct
	public void init() {
		explorerScheduler.scheduleWithFixedDelay(this::dispatchPendingBatches, Duration.ofMillis(3000));
	}

	@PreDestroy
	public void onShutdown() {
		log.info("Shutting down Webhook Dispatcher...");
		dispatchPendingBatches();
	}

	private void loadAllFiltersIntoIndex() {
		List<Webhook> webhooks = webhookCoreService.getAllEnabledWebhooksWithEvents();
		for (Webhook webhook : webhooks) {
			registerWebhookInMemory(webhook);
		}
		log.info("Loaded {} webhooks into memory.", webhooks.size());
	}

	private void registerWebhookInMemory(Webhook webhook) {
		UUID webhookId = webhook.getId();
		Long apiKeyId = webhook.getCreatedByApiKey().getId();
		updateConfigCache(webhook);
		for (WebhookEvent event : webhook.getEvents()) {
			addSubscriptionToIndex(webhookId, event);
		}
		apiKeyToWebhookIds.computeIfAbsent(apiKeyId, k -> ConcurrentHashMap.newKeySet()).add(webhookId);
	}

	private void updateConfigCache(Webhook webhook) {
		Bytes secretKey = webhook.getSecretKey();
		Bytes decryptedSecretKey = aesGCMComponent.decrypt(secretKey);
		webhookConfigs.put(webhook.getId(), new WebhookConfig(webhook.getUrl(), decryptedSecretKey,
				webhook.getDtoVersion() == null ? DEFAULT_DTO_VERSION : webhook.getDtoVersion()));
	}

	// --- EVENT LISTENERS (Update Cache/Index) ---

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleApiKeyUpdate(ApiKeyUpdatedEvent event) {
		Long apiKeyId = event.getApiKeyId();
		ApiKeyUpdatedEvent.UpdateType type = event.getType();
		log.info("Handling ApiKey update: {} for Key ID: {}", type, apiKeyId);
		if (type == ApiKeyUpdatedEvent.UpdateType.DELETE_API_KEY) {
			removeWebhooksByApiKey(apiKeyId);
		} else if (type == ApiKeyUpdatedEvent.UpdateType.UPDATE_API_KEY) {
			event.getApiKey().ifPresent(apiKey -> {
				if (!apiKey.isEnabled()) {
					log.info("ApiKey {} disabled. Unloading associated webhooks.", apiKeyId);
					removeWebhooksByApiKey(apiKeyId);
				} else {
					log.info("ApiKey {} enabled/updated. Reloading associated webhooks.", apiKeyId);
					reloadWebhooksForApiKey(apiKeyId);
				}
			});
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleWebhookUpdate(WebhookUpdateEvent event) {
		UUID webhookId = event.getWebhookId();
		removeWebhookFromIndex(webhookId);
		apiKeyToWebhookIds.values().forEach(set -> set.remove(webhookId));
		switch (event.getType()) {
			case CREATE_WEBHOOK:
			case UPDATE_WEBHOOK:
				Webhook webhook = webhookCoreService.findWebhookByIdWithEvents(webhookId).orElse(null);
				if (webhook != null && webhook.isEnabled() && webhook.getCreatedByApiKey().isEnabled()) {
					registerWebhookInMemory(webhook);
					log.info("Updated cache for webhook {}", webhookId);
				} else {
					webhookConfigs.remove(webhookId);
					pendingBatches.remove(webhookId);
				}
				break;
			case DELETE_WEBHOOK:
				webhookConfigs.remove(webhookId);
				pendingBatches.remove(webhookId);
				break;
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleWebhookEventsUpdate(WebhookEventsUpdateEvent event) {
		UUID webhookId = event.getWebhookId();
		if (!webhookConfigs.containsKey(webhookId))
			return;

		switch (event.getType()) {
			case ADD_EVENTS:
				for (WebhookEventFilter filter : event.getEvents()) {
					addSubscriptionToIndex(webhookId, filter.getType(), filter.getAddressFilter(),
							filter.getTokenAddressFilter());
				}
				break;
			case REMOVE_EVENTS:
				for (WebhookEventFilter filter : event.getEvents()) {
					removeSubscriptionFromIndex(webhookId, filter.getType(), filter.getAddressFilter(),
							filter.getTokenAddressFilter());
				}
				break;
		}
	}

	// --- INDEX MANIPULATION ---

	private void addSubscriptionToIndex(UUID webhookId, WebhookEvent event) {
		addSubscriptionToIndex(webhookId, event.getType(), event.getAddressFilter(), event.getTokenAddressFilter());
	}

	private void addSubscriptionToIndex(UUID webhookId, WebhookEventType type, Address addressFilter,
			Address tokenAddressFilter) {
		WebhookSubscription subscription = new WebhookSubscription(webhookId, tokenAddressFilter);
		if (type == WebhookEventType.NEW_BLOCK) {
			newBlockSubscriptions.add(subscription);
		} else if (type == WebhookEventType.ADDRESS_ACTIVITY && addressFilter != null) {
			addressSubscriptions.computeIfAbsent(addressFilter, k -> ConcurrentHashMap.newKeySet()).add(subscription);
		}
	}

	private void removeSubscriptionFromIndex(UUID webhookId, WebhookEventType type, Address addressFilter,
			Address tokenAddressFilter) {
		if (type == WebhookEventType.NEW_BLOCK) {
			newBlockSubscriptions.removeIf(sub -> sub.getWebhookId().equals(webhookId) &&
					java.util.Objects.equals(sub.getTokenAddressFilter(), tokenAddressFilter));
		} else if (type == WebhookEventType.ADDRESS_ACTIVITY && addressFilter != null) {
			Set<WebhookSubscription> subs = addressSubscriptions.get(addressFilter);
			if (subs != null) {
				subs.removeIf(sub -> sub.getWebhookId().equals(webhookId) &&
						java.util.Objects.equals(sub.getTokenAddressFilter(), tokenAddressFilter));
			}
		}
	}

	private void removeWebhookFromIndex(UUID webhookId) {
		newBlockSubscriptions.removeIf(sub -> sub.getWebhookId().equals(webhookId));
		for (Set<WebhookSubscription> subscriptions : addressSubscriptions.values()) {
			subscriptions.removeIf(sub -> sub.getWebhookId().equals(webhookId));
		}
	}

	private void removeWebhooksByApiKey(Long apiKeyId) {
		Set<UUID> webhookIds = apiKeyToWebhookIds.remove(apiKeyId);
		if (webhookIds != null) {
			for (UUID webhookId : webhookIds) {
				webhookConfigs.remove(webhookId);
				pendingBatches.remove(webhookId);
				removeWebhookFromIndex(webhookId);
			}
			log.info("Unloaded {} webhooks for ApiKey ID {}", webhookIds.size(), apiKeyId);
		}
	}

	private void reloadWebhooksForApiKey(Long apiKeyId) {
		removeWebhooksByApiKey(apiKeyId);
		List<Webhook> webhooks = webhookCoreService.findEnabledByApiKeyIdWithEvents(apiKeyId);
		for (Webhook webhook : webhooks) {
			registerWebhookInMemory(webhook);
		}
		log.info("Reloaded {} webhooks for ApiKey ID {}", webhooks.size(), apiKeyId);
	}

	// --- PROCESSING LOGIC ---

	public void processNewBlockEvent(Block block) {
		for (WebhookSubscription sub : newBlockSubscriptions) {
			// WebhookConfig config = webhookConfigs.get(sub.getWebhookId());
			// if (config.getDtoVersion() == 1) {
			// TODO
			// }
			WebhookEventDtoV1.NewBlockEvent event = new WebhookEventDtoV1.NewBlockEvent(
					WebhookEventType.NEW_BLOCK,
					blockchainBlockHeaderMapper.mapBlock(block));
			queuePayload(sub.getWebhookId(), event);
		}
	}

	public void processAddressActivityEvent(Block block, Tx tx, WebhookTxStatus status, Integer index) {
		Set<Address> involvedAddresses = getAddressesFromTx(tx);
		Set<UUID> targetWebhookIds = new HashSet<>();

		if (involvedAddresses.isEmpty()) {
			return;
		}

		for (Address addr : involvedAddresses) {
			Set<WebhookSubscription> matches = addressSubscriptions.get(addr);
			if (matches != null) {
				for (WebhookSubscription sub : matches) {
					if (matchesTokenFilter(tx, sub.getTokenAddressFilter())) {
						targetWebhookIds.add(sub.getWebhookId());
					}
				}
			}
		}

		for (UUID webhookId : targetWebhookIds) {
			// WebhookConfig config = webhookConfigs.get(webhookId);
			// if (config.getDtoVersion() == 1) {
			// TODO
			// }
			WebhookEventDtoV1.AddressActivityEvent event = new WebhookEventDtoV1.AddressActivityEvent(
					WebhookEventType.ADDRESS_ACTIVITY,
					blockchainTxMapper.mapTx(block, tx, index),
					status);

			queuePayload(webhookId, event);
		}
	}

	private void queuePayload(UUID webhookId, WebhookEventDtoV1 payload) {
		if (!webhookConfigs.containsKey(webhookId))
			return;

		java.util.Queue<Object> queue = pendingBatches.computeIfAbsent(webhookId, k -> {
			java.util.Queue<Object> q = new java.util.concurrent.ConcurrentLinkedQueue<>();
			registry.gaugeCollectionSize("webhook.queue.size", Tags.of("webhookId", webhookId.toString()), q);
			return q;
		});

		if (queue.size() >= MAX_QUEUE_SIZE_PER_WEBHOOK) {
			registry.counter("webhook.queue.dropped", "webhookId", webhookId.toString(), "reason", "full").increment();
			if (queue.size() % 1000 == 0) {
				log.warn("Webhook {} queue full. Dropping events.", webhookId);
			}
			queue.poll();
		}
		queue.add(payload);
	}

	// --- DISPATCH LOGIC ---

	public void dispatchPendingBatches() {
		for (Map.Entry<UUID, java.util.Queue<Object>> entry : pendingBatches.entrySet()) {
			UUID webhookId = entry.getKey();
			java.util.Queue<Object> queue = entry.getValue();

			// Get current config (URL, Secret) from cache
			WebhookConfig config = webhookConfigs.get(webhookId);
			if (config == null) {
				// Config disappeared (webhook was deleted/disabled), clear queue
				queue.clear();
				pendingBatches.remove(webhookId);
				continue;
			}

			if (queue.isEmpty())
				continue;

			List<Object> batchToSend = new java.util.ArrayList<>(MAX_BATCH_SIZE);
			int count = 0;
			while (!queue.isEmpty() && count < MAX_BATCH_SIZE) {
				Object item = queue.poll();
				if (item != null) {
					batchToSend.add(item);
					count++;
				}
			}

			if (!batchToSend.isEmpty()) {
				registry.summary("webhook.batch.size", "webhookId", webhookId.toString()).record(batchToSend.size());
				sendBatch(config, webhookId, batchToSend);
			}
		}
	}

	private void sendBatch(WebhookConfig config, UUID webhookId, List<Object> payloads) {
		try {
			byte[] jsonBytes = objectMapper.writeValueAsBytes(payloads);
			String timestamp = String.valueOf(Instant.now().getEpochSecond());
			String signature = calculateSignature(config.secretKey, timestamp, jsonBytes);

			RequestBody body = RequestBody.create(jsonBytes, JSON);
			Request request = new Request.Builder()
					.url(config.url)
					.addHeader("X-Webhook-Timestamp", timestamp)
					.addHeader("X-Webhook-Signature", signature)
					.post(body)
					.build();

			Timer.Sample sample = Timer.start(registry);

			okHttpClient.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					sample.stop(registry.timer("webhook.delivery.latency", "webhookId", webhookId.toString(), "status",
							"error"));
					log.warn("Webhook delivery failed {}: {}", config.url, e.getMessage());
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					try (response) {
						sample.stop(registry.timer("webhook.delivery.latency", "webhookId", webhookId.toString(),
								"status", String.valueOf(response.code())));
						if (!response.isSuccessful()) {
							log.warn("Webhook error {}: Code {}", config.url, response.code());
						}
					}
				}
			});

		} catch (Exception e) {
			log.error("Error sending webhook batch", e);
		}
	}

	// --- HELPERS ---

	private Set<Address> getAddressesFromTx(Tx tx) {
		Set<Address> addresses = new HashSet<>();
		if (tx.getSender() != null)
			addresses.add(tx.getSender());
		if (tx.getRecipient() != null)
			addresses.add(tx.getRecipient());
		return addresses;
	}

	private boolean matchesTokenFilter(Tx tx, Address tokenAddressFilter) {
		return tokenAddressFilter == null
				|| (tx.getTokenAddress() != null && tx.getTokenAddress().equals(tokenAddressFilter));
	}

	private String calculateSignature(Bytes secretKey, String timestamp, byte[] bodyBytes) {
		final String ALGORITHM = "HmacSHA256";
		try {
			SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.toArray(), ALGORITHM);
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(secretKeySpec);
			mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) '.');
			return Base64.getEncoder().encodeToString(mac.doFinal(bodyBytes));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new GERuntimeException("Error creating HMAC signature", e);
		}
	}

	// --- INNER CLASSES ---

	/** Immutable config snapshot */
	@Data
	@AllArgsConstructor
	private static class WebhookConfig {
		String url;
		Bytes secretKey;
		int dtoVersion;
	}

	/** Index entry - only IDs */
	@Data
	@EqualsAndHashCode(of = { "webhookId", "tokenAddressFilter" })
	@AllArgsConstructor
	private static class WebhookSubscription {
		UUID webhookId;
		Address tokenAddressFilter;
	}
}