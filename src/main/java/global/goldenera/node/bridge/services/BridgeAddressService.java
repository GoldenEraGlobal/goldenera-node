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

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeAddressNonceDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressInDtoV1;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.utils.WebhookValidator;
import global.goldenera.node.shared.utils.WebhookValidator.UrlData;
import lombok.RequiredArgsConstructor;

@Service
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BridgeAddressService {

    static final String WEBHOOK_LABEL_PREFIX = "bridge_";

    private final BridgeNetworkValidator networkValidator;
    private final ChainHeadStateCache chainHeadStateCache;
    private final GeneralProperties generalProperties;
    private final ObjectProvider<WebhookCoreService> webhookCoreService;
    private final BridgeSubscriptionRepository bridgeSubscriptionRepository;

    public BridgeAddressNonceDtoV1 getNonce(Address address, Network network) {
        networkValidator.validate(network);
        long nextNonce = chainHeadStateCache.getHeadState().getNonce(address).getNonce() + 1L;
        return new BridgeAddressNonceDtoV1(
                network,
                address.toChecksumAddress(),
                BigInteger.valueOf(nextNonce));
    }

    @Transactional(rollbackFor = Exception.class)
    public BridgeSubscribeAddressDtoV1 subscribe(BridgeSubscribeAddressInDtoV1 input, ApiKey apiKey) {
        if (input == null || input.address() == null || input.address().isBlank()) {
            throw new GEValidationException("Address is required");
        }
        if (input.webhookUrl() == null || input.webhookUrl().isBlank()) {
            throw new GEValidationException("webhookUrl is required");
        }
        networkValidator.validate(input.network());
        requirePermission(apiKey);
        if (!generalProperties.isWebhookEnable()) {
            throw new GEFailedException("Bridge subscriptions require webhooks to be enabled");
        }
        if (apiKey.getWebhookSecretKey() == null) {
            throw new GEValidationException(
                    "This legacy API key has no webhook signing secret; create a new API key before subscribing");
        }
        Address address = Address.fromHexString(input.address());
        UrlData urlData = WebhookValidator.url(input.webhookUrl());
        WebhookCoreService coreService = requireWebhookCoreService();
        String destinationKey = destinationKey(urlData);
        Webhook destination = bridgeSubscriptionRepository
                .findReusableDestination(apiKey.getId(), destinationKey)
                .orElseGet(() -> createDestination(coreService, apiKey, destinationKey));
        if (!destination.isEnabled()) {
            destination.setEnabled(true);
            destination = coreService.update(destination);
        }

        BridgeSubscription subscription = bridgeSubscriptionRepository
                .findByDestinationIdAndNetworkAndAddress(destination.getId(), input.network(), address)
                .orElse(null);
        if (subscription == null) {
            subscription = bridgeSubscriptionRepository.persist(
                    new BridgeSubscription(destination, input.network(), address));
        } else if (!subscription.isEnabled()) {
            subscription.setEnabled(true);
            subscription = bridgeSubscriptionRepository.update(subscription);
        }
        return new BridgeSubscribeAddressDtoV1(subscription.getId().toString());
    }

    @Transactional(rollbackFor = Exception.class)
    public void unsubscribe(UUID subscriptionId, Network network, ApiKey apiKey) {
        networkValidator.validate(network);
        requirePermission(apiKey);
        BridgeSubscription subscription = bridgeSubscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null
                || subscription.getNetwork() != network
                || subscription.getDestination().getType() != WebhookType.BRIDGE
                || !subscription.getDestination().getCreatedByApiKey().equals(apiKey)) {
            throw new GENotFoundException("Bridge subscription not found");
        }
        if (!subscription.isEnabled()) {
            return;
        }
        subscription.setEnabled(false);
        bridgeSubscriptionRepository.update(subscription);
        if (bridgeSubscriptionRepository.countEnabledByDestinationId(subscription.getDestination().getId()) == 0L) {
            Webhook destination = subscription.getDestination();
            destination.setEnabled(false);
            requireWebhookCoreService().update(destination);
        }
    }

    private WebhookCoreService requireWebhookCoreService() {
        WebhookCoreService service = webhookCoreService.getIfAvailable();
        if (service == null) {
            throw new GEFailedException("Bridge subscriptions require PostgreSQL and webhooks to be enabled");
        }
        return service;
    }

    private void requirePermission(ApiKey apiKey) {
        if (!apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)) {
            throw new GEValidationException("You do not have permission to manage bridge subscriptions");
        }
    }

    private Webhook createDestination(
            WebhookCoreService coreService,
            ApiKey apiKey,
            String destinationKey) {
        String label = WEBHOOK_LABEL_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Webhook destination = new Webhook(
                WebhookType.BRIDGE,
                1,
                label,
                "XLibs bridge destination",
                destinationKey,
                apiKey,
                Collections.emptyMap(),
                Collections.emptyMap());
        destination.setBridgeDestinationKey(destinationKey);
        return coreService.create(destination);
    }

    static String destinationKey(UrlData urlData) {
        if (urlData.getQueryParams().isEmpty()) {
            return urlData.getUrl();
        }
        String query = urlData.getQueryParams().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return urlData.getUrl() + "?" + query;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
