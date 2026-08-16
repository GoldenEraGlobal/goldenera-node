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

import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.bridge.entities.BridgeDelivery;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepository;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "ge.general.postgresql-enable", havingValue = "true")
public class BridgeDeliveryStore {

	BridgeDeliveryRepository repository;

	@Transactional
	public List<ClaimedDelivery> claimAvailable(String owner, Instant now, Duration leaseDuration, int limit) {
		List<BridgeDelivery> deliveries = repository.findClaimableForUpdate(now, PageRequest.of(0, limit));
		List<ClaimedDelivery> claimed = new ArrayList<>(deliveries.size());
		for (BridgeDelivery delivery : deliveries) {
			delivery.claim(owner, now.plus(leaseDuration), now);
			Webhook destination = delivery.getDestination();
			ApiKey apiKey = destination.getCreatedByApiKey();
			Bytes encryptedSecret = destination.getSecretKey() != null
					? destination.getSecretKey()
					: apiKey.getWebhookSecretKey();
			claimed.add(new ClaimedDelivery(
					delivery.getDeliveryId(),
					delivery.getEventId(),
					delivery.getId(),
					delivery.getAttempts(),
					delivery.getBody(),
					destination.getBridgeDestinationKey() == null
							? destination.getUrl()
							: destination.getBridgeDestinationKey(),
					encryptedSecret));
		}
		return claimed;
	}

	@Transactional
	public boolean markDelivered(UUID deliveryId, String owner, int httpStatus, Instant now) {
		BridgeDelivery delivery = ownedInFlight(deliveryId, owner);
		if (delivery == null) {
			return false;
		}
		delivery.markDelivered(httpStatus, now);
		return true;
	}

	@Transactional
	public boolean markRetry(UUID deliveryId, String owner, Integer httpStatus, String error,
			Instant nextAttemptAt, Instant now) {
		BridgeDelivery delivery = ownedInFlight(deliveryId, owner);
		if (delivery == null) {
			return false;
		}
		delivery.markRetry(httpStatus, truncate(error), nextAttemptAt, now);
		return true;
	}

	@Transactional
	public boolean markDead(UUID deliveryId, String owner, Integer httpStatus, String error, Instant now) {
		BridgeDelivery delivery = ownedInFlight(deliveryId, owner);
		if (delivery == null) {
			return false;
		}
		delivery.markDead(httpStatus, truncate(error), now);
		return true;
	}

	private BridgeDelivery ownedInFlight(UUID deliveryId, String owner) {
		return repository.findByDeliveryId(deliveryId)
				.filter(delivery -> delivery.getState() == BridgeDeliveryState.IN_FLIGHT)
				.filter(delivery -> owner.equals(delivery.getLeaseOwner()))
				.orElse(null);
	}

	private String truncate(String error) {
		if (error == null || error.length() <= 2048) {
			return error;
		}
		return error.substring(0, 2048);
	}

	@Getter
	@AllArgsConstructor
	@FieldDefaults(level = PRIVATE, makeFinal = true)
	public static class ClaimedDelivery {
		UUID deliveryId;
		UUID eventId;
		long sequence;
		int attempt;
		String body;
		String url;
		Bytes encryptedSecret;
	}
}
