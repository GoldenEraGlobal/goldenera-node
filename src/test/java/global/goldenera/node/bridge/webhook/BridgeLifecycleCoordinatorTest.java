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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepository;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepositoryCustom.BridgeDeliveryReservation;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.shared.entities.Webhook;

class BridgeLifecycleCoordinatorTest {
	private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000300");

	private final BridgeSubscriptionRepository subscriptionRepository = mock(BridgeSubscriptionRepository.class);
	private final BridgeDeliveryRepository deliveryRepository = mock(BridgeDeliveryRepository.class);
	private final BlockchainTxMapper txMapper = mock(BlockchainTxMapper.class);
	private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
	private final BridgeLifecycleCoordinator coordinator = new BridgeLifecycleCoordinator(
			subscriptionRepository,
			deliveryRepository,
			txMapper,
			objectMapper);

	@Test
	void matchingAddressesShareOneIdempotentDestinationDelivery() throws Exception {
		Tx tx = mock(Tx.class);
		Hash txHash = Hash.fromHexString("0x" + "01".repeat(32));
		Address sender = Address.fromHexString("0x" + "02".repeat(20));
		Address recipient = Address.fromHexString("0x" + "03".repeat(20));
		when(tx.getNetwork()).thenReturn(Network.TESTNET);
		when(tx.getHash()).thenReturn(txHash);
		when(tx.getSender()).thenReturn(sender);
		when(tx.getRecipient()).thenReturn(recipient);
		MempoolEntry entry = new MempoolEntry(tx, Instant.parse("2026-08-16T12:00:00Z"), 10L, null);

		UUID destinationId = UUID.randomUUID();
		Webhook destination = new Webhook();
		destination.setId(destinationId);
		BridgeSubscription senderSubscription = subscription(destination, UUID.fromString(
				"00000000-0000-0000-0000-000000000002"), sender);
		BridgeSubscription recipientSubscription = subscription(destination, UUID.fromString(
				"00000000-0000-0000-0000-000000000001"), recipient);
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(1), isNull(), eq(Long.MAX_VALUE), nullable(Long.class)))
				.thenReturn(List.of(senderSubscription, recipientSubscription));
		when(deliveryRepository.reserve(any(), eq(destinationId), any()))
				.thenReturn(Optional.of(new BridgeDeliveryReservation(42L, UUID.randomUUID())));
		when(deliveryRepository.setBodyOnce(eq(42L), any(), any())).thenReturn(true);

		coordinator.pending(entry, "NEW");

		ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
		verify(deliveryRepository).reserve(eventId.capture(), eq(destinationId), any());
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(deliveryRepository).setBodyOnce(eq(42L), body.capture(), any());
		assertThat(objectMapper.readTree(body.getValue()).path("eventId").asText())
				.isEqualTo(eventId.getValue().toString());
		assertThat(objectMapper.readTree(body.getValue()).path("sequence").asLong()).isEqualTo(42L);
		assertThat(objectMapper.readTree(body.getValue()).path("status").asText()).isEqualTo("PENDING");
		assertThat(List.of(
				objectMapper.readTree(body.getValue()).path("subscriptionIds").get(0).asText(),
				objectMapper.readTree(body.getValue()).path("subscriptionIds").get(1).asText()))
				.containsExactly(
						"00000000-0000-0000-0000-000000000001",
						"00000000-0000-0000-0000-000000000002");
	}

	@Test
	void duplicateReservationDoesNotRewriteDeliveryBody() {
		Tx tx = mock(Tx.class);
		Address sender = Address.fromHexString("0x" + "05".repeat(20));
		when(tx.getNetwork()).thenReturn(Network.TESTNET);
		when(tx.getHash()).thenReturn(Hash.fromHexString("0x" + "04".repeat(32)));
		when(tx.getSender()).thenReturn(sender);
		MempoolEntry entry = new MempoolEntry(tx);
		Webhook destination = new Webhook();
		destination.setId(UUID.randomUUID());
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(1), isNull(), eq(Long.MAX_VALUE), nullable(Long.class)))
				.thenReturn(List.of(subscription(destination, UUID.randomUUID(), sender)));
		when(deliveryRepository.reserve(any(), eq(destination.getId()), any())).thenReturn(Optional.empty());

		coordinator.pending(entry, "SYNC");

		verify(deliveryRepository, never()).setBodyOnce(anyLong(), any(), any());
	}

	@Test
	void stableEventIdsAreRepeatableAndDomainSeparated() {
		UUID first = BridgeLifecycleCoordinator.stableEventId("tx", "hash", "PENDING");
		UUID second = BridgeLifecycleCoordinator.stableEventId("tx", "hash", "PENDING");
		UUID confirmed = BridgeLifecycleCoordinator.stableEventId("tx", "hash", "CONFIRMED");

		assertThat(first).isEqualTo(second).isNotEqualTo(confirmed);
	}

	@Test
	void confirmedBlockRoutesOnlyMatchingAddressActivity() throws Exception {
		Hash blockHash = Hash.fromHexString("0x" + "06".repeat(32));
		Hash txHash = Hash.fromHexString("0x" + "07".repeat(32));
		Address sender = Address.fromHexString("0x" + "08".repeat(20));
		BlockHeader header = mock(BlockHeader.class);
		when(header.getTimestamp()).thenReturn(Instant.parse("2026-08-16T13:00:00Z"));
		Tx tx = mock(Tx.class);
		when(tx.getNetwork()).thenReturn(Network.TESTNET);
		when(tx.getHash()).thenReturn(txHash);
		when(tx.getSender()).thenReturn(sender);
		Block block = mock(Block.class);
		when(block.getHash()).thenReturn(blockHash);
		when(block.getHeight()).thenReturn(12L);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(List.of(tx));
		Webhook destination = new Webhook();
		destination.setId(UUID.randomUUID());
		BridgeSubscription subscription = subscription(destination, UUID.randomUUID(), sender);
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(0), isNull(), eq(Long.MAX_VALUE), nullable(Long.class)))
				.thenReturn(List.of(subscription));
		when(deliveryRepository.reserve(any(), eq(destination.getId()), any()))
				.thenReturn(Optional.of(new BridgeDeliveryReservation(50L, UUID.randomUUID())));
		when(deliveryRepository.setBodyOnce(anyLong(), any(), any())).thenReturn(true);

		coordinator.confirmedBlock(block);

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(deliveryRepository).setBodyOnce(eq(50L), body.capture(), any());
		assertThat(objectMapper.readTree(body.getValue()).path("type").asText())
				.isEqualTo("ADDRESS_ACTIVITY");
		assertThat(objectMapper.readTree(body.getValue()).path("source").asText()).isEqualTo("BLOCKCHAIN");
		assertThat(objectMapper.readTree(body.getValue()).path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(objectMapper.readTree(body.getValue()).path("sequence").asLong()).isEqualTo(50L);
	}

	@Test
	void sourceSequenceIsPassedToActivationAwareRoutingQueries() {
		Address sender = Address.fromHexString("0x" + "09".repeat(20));
		Block block = block(10, transaction(9, sender));

		coordinator.confirmedBlock(block, 101L);

		verify(subscriptionRepository).findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(0), isNull(), eq(101L), eq(10L));
	}

	@Test
	void subscriptionActivatedDuringBacklogExcludesOlderConnectAndReceivesLaterConnect() {
		Webhook destination = new Webhook();
		destination.setId(UUID.randomUUID());
		Address address = Address.fromHexString("0x" + "0a".repeat(20));
		BridgeSubscription subscription = new BridgeSubscription(
				destination, Network.TESTNET,
				address, EPOCH, 100L, EPOCH, 0L, 10L);
		subscription.setId(UUID.randomUUID());
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(0), eq(EPOCH), eq(99L), eq(10L)))
				.thenReturn(List.of());
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(0), eq(EPOCH), eq(101L), eq(11L)))
				.thenReturn(List.of(subscription));
		when(deliveryRepository.reserve(any(), eq(destination.getId()), any()))
				.thenReturn(Optional.of(new BridgeDeliveryReservation(70L, UUID.randomUUID())));
		when(deliveryRepository.setBodyOnce(eq(70L), any(), any())).thenReturn(true);

		coordinator.confirmedBlock(block(10, transaction(10, address)),
				new BridgeSourcePosition(EPOCH, 99L, UUID.randomUUID()));
		coordinator.confirmedBlock(block(11, transaction(11, address)),
				new BridgeSourcePosition(EPOCH, 101L, UUID.randomUUID()));

		verify(deliveryRepository).reserve(any(), eq(destination.getId()), any());
	}

	@Test
	void repeatedSemanticTransitionUsesJournalOccurrenceKeyForDistinctEventIds() {
		Webhook destination = new Webhook();
		destination.setId(UUID.randomUUID());
		Address address = Address.fromHexString("0x" + "0b".repeat(20));
		BridgeSubscription subscription = new BridgeSubscription(
				destination, Network.TESTNET,
				address, EPOCH, 0L, EPOCH, 0L, 0L);
		subscription.setId(UUID.randomUUID());
		when(subscriptionRepository.findEnabledByNetworkAndAddressIn(
				eq(Network.TESTNET), any(), eq(0), eq(EPOCH), anyLong(), anyLong()))
				.thenReturn(List.of(subscription));
		when(deliveryRepository.reserve(any(), eq(destination.getId()), any()))
				.thenReturn(
						Optional.of(new BridgeDeliveryReservation(80L, UUID.randomUUID())),
						Optional.of(new BridgeDeliveryReservation(81L, UUID.randomUUID())));
		when(deliveryRepository.setBodyOnce(anyLong(), any(), any())).thenReturn(true);
		Block block = block(12, transaction(12, address));

		coordinator.confirmedBlock(block, new BridgeSourcePosition(EPOCH, 110L, UUID.randomUUID()));
		coordinator.confirmedBlock(block, new BridgeSourcePosition(EPOCH, 111L, UUID.randomUUID()));

		ArgumentCaptor<UUID> eventIds = ArgumentCaptor.forClass(UUID.class);
		verify(deliveryRepository, times(2))
				.reserve(eventIds.capture(), eq(destination.getId()), any());
		assertThat(eventIds.getAllValues().get(0)).isNotEqualTo(eventIds.getAllValues().get(1));
	}

	private BridgeSubscription subscription(Webhook destination, UUID id, Address address) {
		BridgeSubscription subscription = new BridgeSubscription(
				destination, Network.TESTNET, address, EPOCH, 0L, EPOCH, 0L, 0L);
		subscription.setId(id);
		return subscription;
	}

	private Block block(int value, Tx tx) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getTimestamp()).thenReturn(Instant.parse("2026-08-16T15:00:00Z"));
		Block block = mock(Block.class);
		when(block.getHash()).thenReturn(Hash.fromHexString("0x" + "%064x".formatted(value)));
		when(block.getHeight()).thenReturn((long) value);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(List.of(tx));
		return block;
	}

	private Tx transaction(int value, Address sender) {
		Tx tx = mock(Tx.class);
		when(tx.getNetwork()).thenReturn(Network.TESTNET);
		when(tx.getHash()).thenReturn(Hash.fromHexString("0x" + "%064x".formatted(value + 100)));
		when(tx.getSender()).thenReturn(sender);
		return tx;
	}
}
