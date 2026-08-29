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

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscribeAddressInDtoV1;
import global.goldenera.node.bridge.services.BridgeAddressService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.exceptions.GEValidationException;
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

    @PostMapping("subscribe")
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    public BridgeSubscribeAddressDtoV1 subscribe(
            @RequestBody BridgeSubscribeAddressInDtoV1 input,
            Authentication authentication) {
        return bridgeAddressService.subscribe(input, apiKey(authentication));
    }

    @DeleteMapping("subscription/{subscriptionId}")
    @PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
    public void unsubscribe(
            @PathVariable UUID subscriptionId,
            @RequestParam Network network,
            Authentication authentication) {
        bridgeAddressService.unsubscribe(subscriptionId, network, apiKey(authentication));
    }

    private ApiKey apiKey(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiKey apiKey)) {
            throw new GEValidationException("Invalid authentication");
        }
        return apiKey;
    }
}
