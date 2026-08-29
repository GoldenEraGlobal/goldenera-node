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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepository;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepositoryCustom.BridgeDeliveryReservation;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.bridge.webhook.dtos.BridgeWebhookEventDtoV1;
import global.goldenera.node.bridge.webhook.dtos.BridgeWebhookEventDtoV1.ReorgDataDtoV1;
import global.goldenera.node.bridge.webhook.dtos.BridgeWebhookEventType;
import global.goldenera.node.bridge.webhook.dtos.BridgeWebhookSource;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainTxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
			prefix = "ge.general",
			name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeLifecycleCoordinator {

	private static final String EVENT_ID_NAMESPACE = "goldenera:bridge:lifecycle:v1:";

	private final BridgeSubscriptionRepository subscriptionRepository;
	private final BridgeDeliveryRepository deliveryRepository;
	private final BlockchainTxMapper txMapper;
	private final BlockchainBlockHeaderMapper blockHeaderMapper;
	private final ObjectMapper objectMapper;
	private final GeneralProperties generalProperties;
	private final BridgeGlobalRoutingPolicy globalRoutingPolicy;

	@Transactional(rollbackFor = Exception.class)
	public void pending(MempoolEntry entry, String reason) {
		pending(entry, reason, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void pending(MempoolEntry entry, String reason, long sourceSequence) {
		pending(entry, reason, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void pending(MempoolEntry entry, String reason, BridgeSourcePosition position) {
		pendingInternal(entry, reason, position);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void pendingAfterReorg(MempoolEntry entry) {
		pendingAfterReorg(entry, Long.MAX_VALUE);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void pendingAfterReorg(MempoolEntry entry, long sourceSequence) {
		pendingAfterReorg(entry, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
	public void pendingAfterReorg(MempoolEntry entry, BridgeSourcePosition position) {
		pendingInternal(entry, "REORG", position);
	}

	private void pendingInternal(MempoolEntry entry, String reason, BridgeSourcePosition position) {
		Tx tx = entry.getTx();
		UUID eventId = eventId(position, "tx", tx.getNetwork(), tx.getHash(), WebhookTxStatus.PENDING,
				entry.getFirstSeenHeight(), entry.getFirstSeenTime());
		routeTransaction(
				eventId,
				entry.getFirstSeenTime(),
				tx,
				WebhookTxStatus.PENDING,
				reason,
				null,
				txMapper.map(entry),
				LifecycleJournalStream.MEMPOOL,
				position,
				null);
	}

	@Transactional(rollbackFor = Exception.class)
	public void replaced(MempoolEntry entry, Hash replacementTxHash, String reason) {
		replaced(entry, replacementTxHash, reason, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void replaced(MempoolEntry entry, Hash replacementTxHash, String reason, long sourceSequence) {
		replaced(entry, replacementTxHash, reason, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void replaced(MempoolEntry entry, Hash replacementTxHash, String reason, BridgeSourcePosition position) {
		Tx tx = entry.getTx();
		UUID eventId = eventId(position, "tx", tx.getNetwork(), tx.getHash(), WebhookTxStatus.REPLACED,
				entry.getFirstSeenHeight(), entry.getFirstSeenTime(), replacementTxHash);
		routeTransaction(
				eventId,
				Instant.now(),
				tx,
				WebhookTxStatus.REPLACED,
				reason,
				replacementTxHash,
				txMapper.map(entry),
				LifecycleJournalStream.MEMPOOL,
				position,
				null);
	}

	@Transactional(rollbackFor = Exception.class)
	public void dropped(MempoolEntry entry, String reason) {
		dropped(entry, reason, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void dropped(MempoolEntry entry, String reason, long sourceSequence) {
		dropped(entry, reason, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void dropped(MempoolEntry entry, String reason, BridgeSourcePosition position) {
		Tx tx = entry.getTx();
		UUID eventId = eventId(position, "tx", tx.getNetwork(), tx.getHash(), WebhookTxStatus.DROPPED,
				entry.getFirstSeenHeight(), entry.getFirstSeenTime(), reason);
		routeTransaction(
				eventId,
				Instant.now(),
				tx,
				WebhookTxStatus.DROPPED,
				reason,
				null,
				txMapper.map(entry),
				LifecycleJournalStream.MEMPOOL,
				position,
				null);
	}

	@Transactional(rollbackFor = Exception.class)
	public void confirmedBlock(Block block, List<BlockEvent> blockEvents) {
		confirmedBlock(block, blockEvents, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void confirmedBlock(Block block, List<BlockEvent> blockEvents, long sourceSequence) {
		confirmedBlock(block, blockEvents, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void confirmedBlock(Block block, List<BlockEvent> blockEvents, BridgeSourcePosition position) {
		Network network = generalProperties.getNetwork();
		UUID blockEventId = eventId(position, "block", network, block.getHash(), BridgeWebhookEventType.NEW_BLOCK);
		BridgeWebhookEventDtoV1 blockPayload = BridgeWebhookEventDtoV1.builder()
				.eventId(blockEventId)
				.occurredAt(block.getHeader().getTimestamp())
				.network(network)
				.type(BridgeWebhookEventType.NEW_BLOCK)
					.source(BridgeWebhookSource.CORE)
				.block(blockHeaderMapper.mapBlockWithEvents(block, blockEvents))
				.build();
		if (globalRoutingPolicy.shouldRoute(BridgeWebhookEventType.NEW_BLOCK)) {
			routeGlobal(blockEventId, blockPayload, LifecycleJournalStream.CANONICAL, position, block.getHeight());
		}

		int index = 0;
		for (Tx tx : block.getTxs()) {
			UUID eventId = eventId(position,
					"tx", network, tx.getHash(), WebhookTxStatus.CONFIRMED, block.getHash());
			routeTransaction(
					eventId,
					block.getHeader().getTimestamp(),
					tx,
					WebhookTxStatus.CONFIRMED,
					null,
					null,
					txMapper.mapTx(block, tx, index++),
					LifecycleJournalStream.CANONICAL,
					position,
					block.getHeight());
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public void revertedBlock(Block orphanBlock) {
		revertedBlock(orphanBlock, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void revertedBlock(Block orphanBlock, long sourceSequence) {
		revertedBlock(orphanBlock, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void revertedBlock(Block orphanBlock, BridgeSourcePosition position) {
		Network network = generalProperties.getNetwork();
		int index = 0;
		for (Tx tx : orphanBlock.getTxs()) {
			UUID eventId = eventId(position,
					"tx", network, tx.getHash(), WebhookTxStatus.REVERTED, orphanBlock.getHash());
			routeTransaction(
					eventId,
					Instant.now(),
					tx,
					WebhookTxStatus.REVERTED,
					"REORG",
					null,
					txMapper.mapTx(orphanBlock, tx, index++),
					LifecycleJournalStream.CANONICAL,
					position,
					null);
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public void reorg(Long oldHeight, Hash oldHash, Long newHeight, Hash newHash) {
		reorg(oldHeight, oldHash, newHeight, newHash, Long.MAX_VALUE);
	}

	@Transactional(rollbackFor = Exception.class)
	public void reorg(Long oldHeight, Hash oldHash, Long newHeight, Hash newHash, long sourceSequence) {
		reorg(oldHeight, oldHash, newHeight, newHash, BridgeSourcePosition.live(sourceSequence));
	}

	@Transactional(rollbackFor = Exception.class)
	public void reorg(Long oldHeight, Hash oldHash, Long newHeight, Hash newHash, BridgeSourcePosition position) {
		Network network = generalProperties.getNetwork();
		UUID eventId = eventId(position, "chain", network, BridgeWebhookEventType.REORG, oldHash, newHash);
		BridgeWebhookEventDtoV1 payload = BridgeWebhookEventDtoV1.builder()
				.eventId(eventId)
				.occurredAt(Instant.now())
				.network(network)
				.type(BridgeWebhookEventType.REORG)
					.source(BridgeWebhookSource.CORE)
				.reason("REORG")
				.reorg(ReorgDataDtoV1.builder()
						.oldHeight(oldHeight)
						.oldHash(oldHash)
						.newHeight(newHeight)
						.newHash(newHash)
						.build())
				.build();
		if (globalRoutingPolicy.shouldRoute(BridgeWebhookEventType.REORG)) {
			routeGlobal(eventId, payload, LifecycleJournalStream.CANONICAL, position, null);
		}
	}

	private void routeTransaction(UUID eventId, Instant occurredAt, Tx tx, WebhookTxStatus status,
			String reason, Hash replacementTxHash,
			BlockchainTxDtoV1 data, LifecycleJournalStream stream, BridgeSourcePosition position,
			Long canonicalHeight) {
		Set<Address> addresses = involvedAddresses(tx);
		if (addresses.isEmpty()) {
			return;
		}
		List<BridgeSubscription> subscriptions = subscriptionRepository.findEnabledByNetworkAndAddressIn(
				tx.getNetwork(), addresses, stream.code(), position.epoch(), position.sequence(), canonicalHeight);
		for (DestinationRoute route : groupByDestination(subscriptions).values()) {
			BridgeWebhookEventDtoV1 payload = BridgeWebhookEventDtoV1.builder()
					.eventId(eventId)
					.occurredAt(occurredAt)
					.network(tx.getNetwork())
					.type(BridgeWebhookEventType.ADDRESS_ACTIVITY)
					.source(BridgeWebhookSource.CORE)
					.status(status)
					.reason(reason)
					.subscriptionIds(route.subscriptionIds())
					.data(data)
					.replacementTxHash(replacementTxHash)
					.build();
			reserveAndStore(route.destination(), eventId, payload);
		}
	}

	private void routeGlobal(UUID eventId, BridgeWebhookEventDtoV1 template,
			LifecycleJournalStream stream, BridgeSourcePosition position, Long canonicalHeight) {
		for (DestinationRoute route : groupByDestination(
				subscriptionRepository.findAllEnabledWithDestination(
						template.getNetwork(), stream.code(), position.epoch(), position.sequence(),
						canonicalHeight)).values()) {
			BridgeWebhookEventDtoV1 payload = copyForRoute(template, route.subscriptionIds());
			reserveAndStore(route.destination(), eventId, payload);
		}
	}

	private BridgeWebhookEventDtoV1 copyForRoute(BridgeWebhookEventDtoV1 template, List<UUID> subscriptionIds) {
		return BridgeWebhookEventDtoV1.builder()
				.eventId(template.getEventId())
				.occurredAt(template.getOccurredAt())
				.network(template.getNetwork())
				.type(template.getType())
				.source(template.getSource())
				.status(template.getStatus())
				.reason(template.getReason())
				.subscriptionIds(subscriptionIds)
				.data(template.getData())
				.reorg(template.getReorg())
				.block(template.getBlock())
				.replacementTxHash(template.getReplacementTxHash())
				.build();
	}

	private void reserveAndStore(Webhook destination, UUID eventId, BridgeWebhookEventDtoV1 payload) {
		Instant now = Instant.now();
		BridgeDeliveryReservation reservation = deliveryRepository.reserve(eventId, destination.getId(), now)
				.orElse(null);
		if (reservation == null) {
			return;
		}
		payload.setSequence(reservation.id());
		String body;
		try {
			body = objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException exception) {
			throw new GEFailedException("Failed to serialize bridge lifecycle event", exception);
		}
		if (!deliveryRepository.setBodyOnce(reservation.id(), body, now)) {
			throw new GEFailedException("Failed to initialize reserved bridge delivery");
		}
	}

	private Map<UUID, DestinationRoute> groupByDestination(Collection<BridgeSubscription> subscriptions) {
		Map<UUID, DestinationRoute> routes = new TreeMap<>();
		for (BridgeSubscription subscription : subscriptions) {
			Webhook destination = subscription.getDestination();
			DestinationRoute route = routes.computeIfAbsent(
					destination.getId(), ignored -> new DestinationRoute(destination, new ArrayList<>()));
			route.subscriptionIds().add(subscription.getId());
		}
		for (DestinationRoute route : routes.values()) {
			route.subscriptionIds().sort(UUID::compareTo);
		}
		return routes;
	}

	private Set<Address> involvedAddresses(Tx tx) {
		Set<Address> addresses = new LinkedHashSet<>();
		if (tx.getSender() != null) {
			addresses.add(tx.getSender());
		}
		if (tx.getRecipient() != null) {
			addresses.add(tx.getRecipient());
		}
		return addresses;
	}

	static UUID stableEventId(Object... components) {
		StringBuilder value = new StringBuilder(EVENT_ID_NAMESPACE);
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

	private UUID eventId(BridgeSourcePosition position, Object... components) {
		if (position.eventKey() == null) {
			return stableEventId(components);
		}
		Object[] journalComponents = new Object[components.length + 2];
		journalComponents[0] = "journal";
		journalComponents[1] = position.eventKey();
		System.arraycopy(components, 0, journalComponents, 2, components.length);
		return stableEventId(journalComponents);
	}

	private record DestinationRoute(Webhook destination, List<UUID> subscriptionIds) {
	}
}
