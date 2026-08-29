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
package global.goldenera.node.bridge.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeAddressNonceDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressInDtoV1;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import global.goldenera.node.shared.utils.WebhookValidator;

class BridgeAddressServiceTest {

    @Test
    void returnsZeroAsNextNonceForFreshAccount() {
        Address address = Address.fromHexString(String.format("0x%040x", 1));
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
        WorldState state = mock(WorldState.class);
        AccountNonceState nonce = mock(AccountNonceState.class);
        when(cache.getHeadState()).thenReturn(state);
        when(state.getNonce(address)).thenReturn(nonce);
        when(nonce.getNonce()).thenReturn(-1L);
		BridgeAddressService service = new BridgeAddressService(
				validator,
				cache,
				mock(GeneralProperties.class),
				webhookProvider(),
				subscriptionProvider(mock(BridgeSubscriptionRepository.class)),
				journalProvider());

        BridgeAddressNonceDtoV1 result = service.getNonce(address, Network.MAINNET);

        assertThat(result.network()).isEqualTo(Network.MAINNET);
        assertThat(result.address()).isEqualTo(address.toChecksumAddress());
        assertThat(result.nonce()).isZero();
    }

    @Test
    void incrementsStoredChainNonce() {
        Address address = Address.fromHexString(String.format("0x%040x", 2));
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
        WorldState state = mock(WorldState.class);
        AccountNonceState nonce = mock(AccountNonceState.class);
        when(cache.getHeadState()).thenReturn(state);
        when(state.getNonce(address)).thenReturn(nonce);
        when(nonce.getNonce()).thenReturn(11L);
		BridgeAddressService service = new BridgeAddressService(
				validator,
				cache,
				mock(GeneralProperties.class),
				webhookProvider(),
				subscriptionProvider(mock(BridgeSubscriptionRepository.class)),
				journalProvider());

        assertThat(service.getNonce(address, Network.TESTNET).nonce()).isEqualTo(12L);
    }

    @Test
    void createsBridgeDestinationAndDurableSubscriptionWithoutApplyingWebhookLimit() {
        Address address = Address.fromHexString(String.format("0x%040x", 3));
        UUID subscriptionId = UUID.randomUUID();
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
        GeneralProperties properties = mock(GeneralProperties.class);
        ApiKey apiKey = mock(ApiKey.class);
        WebhookCoreService core = mock(WebhookCoreService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WebhookCoreService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(core);
        when(properties.isWebhookEnable()).thenReturn(true);
        when(apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)).thenReturn(true);
        when(apiKey.getWebhookSecretKey()).thenReturn(Bytes.of(1));
        when(apiKey.getMaxWebhooks()).thenReturn(0L);
        when(apiKey.getId()).thenReturn(9L);
        Webhook destination = mock(Webhook.class);
        UUID destinationId = UUID.randomUUID();
        when(destination.getId()).thenReturn(destinationId);
        when(destination.isEnabled()).thenReturn(true);
        when(core.create(any(Webhook.class))).thenReturn(destination);
        BridgeSubscriptionRepository repository = mock(BridgeSubscriptionRepository.class);
        when(repository.findReusableDestination(9L, "https://consumer.example/webhook"))
                .thenReturn(Optional.empty());
        when(repository.findByDestinationIdAndNetworkAndAddress(destinationId, Network.MAINNET, address))
                .thenReturn(Optional.empty());
		when(repository.upsertEnabled(
				any(UUID.class), eq(destinationId), eq(Network.MAINNET), eq(address),
				any(UUID.class), anyLong(), any(UUID.class), anyLong(),
				eq(321L), any())).thenReturn(subscriptionId);
		HeadStateSnapshot headSnapshot = mock(HeadStateSnapshot.class);
		StoredBlock head = mock(StoredBlock.class);
		when(head.getHeight()).thenReturn(321L);
		when(headSnapshot.head()).thenReturn(head);
		when(cache.getHeadSnapshot()).thenReturn(headSnapshot);
		BridgeAddressService service = new BridgeAddressService(
				validator, cache, properties, provider, subscriptionProvider(repository), journalProvider());

        BridgeSubscribeAddressDtoV1 result = service.subscribe(
                new BridgeSubscribeAddressInDtoV1(
                        Network.MAINNET,
                        address.toChecksumAddress(),
                        "https://consumer.example/webhook"),
                apiKey);

        assertThat(result.subscriptionId()).isEqualTo(subscriptionId.toString());
        ArgumentCaptor<Webhook> webhookCaptor = ArgumentCaptor.forClass(Webhook.class);
        verify(core).create(webhookCaptor.capture());
        assertThat(webhookCaptor.getValue().getType()).isEqualTo(WebhookType.BRIDGE);
        assertThat(webhookCaptor.getValue().getBridgeDestinationKey())
                .isEqualTo("https://consumer.example/webhook");
        assertThat(webhookCaptor.getValue().getUrl()).isEqualTo("https://consumer.example/webhook");
        assertThat(webhookCaptor.getValue().getQueryParams()).isEmpty();
		verify(repository).lockDestination(9L, "https://consumer.example/webhook");
		verify(core).flush();
		verify(repository).upsertEnabled(
				any(UUID.class), eq(destinationId), eq(Network.MAINNET), eq(address),
				any(UUID.class), eq(0L), any(UUID.class), eq(0L), eq(321L), any());
        verify(core, never()).getCountByApiKeyId(apiKey.getId());
    }

    @Test
    void hidesSubscriptionOwnedByAnotherApiKey() {
        UUID id = UUID.randomUUID();
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        ApiKey caller = mock(ApiKey.class);
        ApiKey owner = mock(ApiKey.class);
        when(caller.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)).thenReturn(true);
        Webhook destination = mock(Webhook.class);
        when(destination.getType()).thenReturn(WebhookType.BRIDGE);
        when(destination.getCreatedByApiKey()).thenReturn(owner);
        BridgeSubscription subscription = mock(BridgeSubscription.class);
        when(subscription.getNetwork()).thenReturn(Network.MAINNET);
        when(subscription.getDestination()).thenReturn(destination);
        WebhookCoreService core = mock(WebhookCoreService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WebhookCoreService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(core);
        BridgeSubscriptionRepository repository = mock(BridgeSubscriptionRepository.class);
        when(repository.findById(id)).thenReturn(Optional.of(subscription));
		BridgeAddressService service = new BridgeAddressService(
				validator,
				mock(ChainHeadStateCache.class),
				mock(GeneralProperties.class),
				provider,
				subscriptionProvider(repository),
				journalProvider());

        assertThatThrownBy(() -> service.unsubscribe(id, Network.MAINNET, caller))
                .isInstanceOf(GENotFoundException.class);
    }

    @Test
    void destinationKeyIncludesCanonicalQueryParameters() {
        assertThat(BridgeAddressService.destinationKey(
                WebhookValidator.url(
                        "https://consumer.example/webhook?z=last&a=hello%20world")))
                .isEqualTo("https://consumer.example/webhook?a=hello+world&z=last");
    }

    @Test
    void storesCanonicalFullUrlOnInternalDestination() {
        Address address = Address.fromHexString(String.format("0x%040x", 4));
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        GeneralProperties properties = mock(GeneralProperties.class);
        ApiKey apiKey = mock(ApiKey.class);
        WebhookCoreService core = mock(WebhookCoreService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WebhookCoreService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(core);
        when(properties.isWebhookEnable()).thenReturn(true);
        when(apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)).thenReturn(true);
        when(apiKey.getWebhookSecretKey()).thenReturn(Bytes.of(1));
        when(apiKey.getId()).thenReturn(10L);
        Webhook persistedDestination = mock(Webhook.class);
        UUID destinationId = UUID.randomUUID();
        when(persistedDestination.getId()).thenReturn(destinationId);
        when(persistedDestination.isEnabled()).thenReturn(true);
        when(core.create(any(Webhook.class))).thenReturn(persistedDestination);
		UUID subscriptionId = UUID.randomUUID();
        BridgeSubscriptionRepository repository = mock(BridgeSubscriptionRepository.class);
        String canonicalUrl = "https://consumer.example/webhook?a=hello+world&z=last";
        when(repository.findReusableDestination(10L, canonicalUrl)).thenReturn(Optional.empty());
        when(repository.findByDestinationIdAndNetworkAndAddress(destinationId, Network.MAINNET, address))
                .thenReturn(Optional.empty());
        when(repository.upsertEnabled(
				any(UUID.class), eq(destinationId), eq(Network.MAINNET), eq(address),
				any(UUID.class), anyLong(), any(UUID.class), anyLong(),
				anyLong(), any())).thenReturn(subscriptionId);
		BridgeAddressService service = new BridgeAddressService(
				validator, mock(ChainHeadStateCache.class), properties, provider,
				subscriptionProvider(repository), journalProvider());

        service.subscribe(
                new BridgeSubscribeAddressInDtoV1(
                        Network.MAINNET,
                        address.toChecksumAddress(),
                        "https://consumer.example/webhook?z=last&a=hello%20world"),
                apiKey);

        ArgumentCaptor<Webhook> webhookCaptor = ArgumentCaptor.forClass(Webhook.class);
        verify(core).create(webhookCaptor.capture());
        assertThat(webhookCaptor.getValue().getUrl()).isEqualTo(canonicalUrl);
        assertThat(webhookCaptor.getValue().getBridgeDestinationKey()).isEqualTo(canonicalUrl);
        assertThat(webhookCaptor.getValue().getQueryParams()).isEqualTo(Collections.emptyMap());
    }

    @Test
    void unsubscribeDisablesLastSubscriptionAndDestinationWithoutDeletingHistory() {
        UUID subscriptionId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        BridgeNetworkValidator validator = mock(BridgeNetworkValidator.class);
        ApiKey apiKey = mock(ApiKey.class);
        when(apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)).thenReturn(true);
        Webhook destination = mock(Webhook.class);
        when(destination.getId()).thenReturn(destinationId);
        when(destination.getType()).thenReturn(WebhookType.BRIDGE);
        when(destination.getCreatedByApiKey()).thenReturn(apiKey);
        BridgeSubscription subscription = mock(BridgeSubscription.class);
        when(subscription.getNetwork()).thenReturn(Network.MAINNET);
        when(subscription.getDestination()).thenReturn(destination);
        when(subscription.isEnabled()).thenReturn(true);
        WebhookCoreService core = mock(WebhookCoreService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WebhookCoreService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(core);
        BridgeSubscriptionRepository repository = mock(BridgeSubscriptionRepository.class);
        when(repository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(repository.countEnabledByDestinationId(destinationId)).thenReturn(0L);
		BridgeAddressService service = new BridgeAddressService(
				validator,
				mock(ChainHeadStateCache.class),
				mock(GeneralProperties.class),
				provider,
				subscriptionProvider(repository),
				journalProvider());

        service.unsubscribe(subscriptionId, Network.MAINNET, apiKey);

        verify(subscription).setEnabled(false);
        verify(repository).update(subscription);
        verify(destination).setEnabled(false);
        verify(core).update(destination);
        verify(repository, never()).delete(subscription);
    }

    @SuppressWarnings("unchecked")
	private static ObjectProvider<WebhookCoreService> webhookProvider() {
		return mock(ObjectProvider.class);
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<BridgeSubscriptionRepository> subscriptionProvider(
			BridgeSubscriptionRepository repository) {
		ObjectProvider<BridgeSubscriptionRepository> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(repository);
		return provider;
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<LifecycleJournalQuery> journalProvider() {
		UUID epoch = UUID.fromString("00000000-0000-0000-0000-000000000100");
		LifecycleJournalQuery query = mock(LifecycleJournalQuery.class);
		when(query.head(LifecycleJournalStream.CANONICAL)).thenReturn(
				new LifecycleJournalHead(LifecycleJournalStream.CANONICAL, epoch, 0L, 1L, -1L, Hash.ZERO));
		when(query.head(LifecycleJournalStream.MEMPOOL)).thenReturn(
				new LifecycleJournalHead(LifecycleJournalStream.MEMPOOL, epoch, 0L, 1L, -1L, Hash.ZERO));
		ObjectProvider<LifecycleJournalQuery> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(query);
		return provider;
	}
}
