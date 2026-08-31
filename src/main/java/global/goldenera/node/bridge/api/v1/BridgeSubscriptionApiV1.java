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
package global.goldenera.node.bridge.api.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressInDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscriptionDtoV1_Page;
import global.goldenera.node.bridge.api.v1.dtos.BridgeUnsubscribeDtoV1;
import global.goldenera.node.bridge.api.v1.mappers.BridgeSubscriptionMapper;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.services.BridgeAddressService;
import global.goldenera.node.bridge.services.BridgeSubscriptionService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.exceptions.GEValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bridge/v1/address")
@ConditionalOnProperty(
        prefix = "ge.general",
        name = { "postgresql-enable", "webhook-enable" },
        havingValue = "true")
public class BridgeSubscriptionApiV1 {

    private final BridgeAddressService bridgeAddressService;
    private final BridgeSubscriptionService bridgeSubscriptionService;
    private final BridgeSubscriptionMapper bridgeSubscriptionMapper;
    private final ObjectMapper objectMapper;

    @GetMapping("subscription/page")
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    public BridgeSubscriptionDtoV1_Page getPage(
            @RequestParam int pageNumber,
            @RequestParam int pageSize,
            @RequestParam(required = false) Sort.Direction direction,
            @RequestParam(required = false) Address address,
            @RequestParam(defaultValue = "ACTIVE") BridgeSubscriptionStatus status,
            Authentication authentication) {
        return bridgeSubscriptionMapper.map(bridgeSubscriptionService.getPage(
                pageNumber, pageSize, direction, address, status, apiKey(authentication)));
    }

    @PostMapping("subscribe")
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    public BridgeSubscribeAddressDtoV1 subscribe(
            @RequestBody BridgeSubscribeAddressInDtoV1 input,
            Authentication authentication) {
        return bridgeAddressService.subscribe(input, apiKey(authentication));
    }

    @DeleteMapping("subscription/{subscriptionId}")
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    public BridgeUnsubscribeDtoV1 unsubscribe(
            @PathVariable UUID subscriptionId,
            Authentication authentication) {
        return bridgeAddressService.unsubscribe(subscriptionId, apiKey(authentication));
    }

    @DeleteMapping(value = "subscription", consumes = { MediaType.APPLICATION_JSON_VALUE, "application/*+json" })
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    @Operation(description = "Unsubscribe this API key's subscriptions. Send JSON null for all, or an array of UUIDs. "
            + "An empty array changes nothing. An absent body is rejected. History is retained.")
    public BridgeUnsubscribeDtoV1 unsubscribeBatch(
            @RequestBody @Schema(implementation = UUID[].class, nullable = true) String body,
            Authentication authentication) {
        return bridgeAddressService.unsubscribeBatch(subscriptionIds(body), apiKey(authentication));
    }

    private List<UUID> subscriptionIds(String body) {
        JsonNode input;
        try {
            // A malformed body starting with null must never trigger unsubscribe-all.
            input = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
        } catch (JsonProcessingException exception) {
            throw new GEValidationException("Body must contain exactly one JSON value: null or an array of subscription UUIDs");
        }
        if (input.isNull()) {
            return null;
        }
        if (!input.isArray()) {
            throw new GEValidationException("Body must be JSON null or an array of subscription UUIDs");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode value : input) {
            if (!value.isTextual()) {
                throw new GEValidationException("Each subscription ID must be a UUID string");
            }
            try {
                UUID id = UUID.fromString(value.textValue());
                if (!id.toString().equalsIgnoreCase(value.textValue())) {
                    throw new IllegalArgumentException("Non-canonical UUID");
                }
                ids.add(id);
            } catch (IllegalArgumentException exception) {
                throw new GEValidationException("Invalid subscription UUID: " + value.textValue());
            }
        }
        return ids;
    }

    private ApiKey apiKey(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiKey apiKey)) {
            throw new GEValidationException("Invalid authentication");
        }
        return apiKey;
    }
}
