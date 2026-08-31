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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.admin.api.v1.bridge.AdminBridgeApiV1;
import global.goldenera.node.admin.api.v1.bridge.mappers.AdminBridgeDeliveryMapper;
import global.goldenera.node.admin.api.v1.bridge.mappers.AdminBridgeSubscriptionMapper;
import global.goldenera.node.bridge.api.v1.mappers.BridgeDeliveryMapper;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.services.BridgeDeliveryService;
import global.goldenera.node.bridge.services.BridgeDeliveryService.DeliveryFilter;
import global.goldenera.node.bridge.services.BridgeSubscriptionService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.properties.GeneralProperties;

class BridgeAuditApiV1Test {

    private final BridgeDeliveryService deliveries = mock(BridgeDeliveryService.class);
    private final BridgeSubscriptionService subscriptions = mock(BridgeSubscriptionService.class);
    private final ApiKey apiKey = mock(ApiKey.class);
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(apiKey, null, List.of());
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        GeneralProperties properties = new GeneralProperties();
        properties.setNetwork(Network.TESTNET);
        mvc = MockMvcBuilders.standaloneSetup(
                new BridgeDeliveryApiV1(deliveries, new BridgeDeliveryMapper(properties)),
                new AdminBridgeApiV1(deliveries, subscriptions,
                        new AdminBridgeDeliveryMapper(), new AdminBridgeSubscriptionMapper())).build();
    }

    @Test
    void auditBindsFiltersAndUsesAuthenticatedKeyEvenWhenAnotherKeyIsRequested() throws Exception {
        UUID destinationId = UUID.randomUUID();
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        when(deliveries.getPage(eq(0), eq(10), isNull(), any(), eq(apiKey))).thenReturn(Page.empty());
        mvc.perform(get("/api/bridge/v1/delivery/page").principal(authentication)
                .param("pageNumber", "0").param("pageSize", "10").param("apiKeyId", "999")
                .param("destinationId", destinationId.toString()).param("state", "RETRY")
                .param("createdFrom", from.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.network").value("TESTNET"))
                .andExpect(jsonPath("$.list").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
        verify(deliveries).getPage(0, 10, null,
                new DeliveryFilter(destinationId, null, null, BridgeDeliveryState.RETRY, from, null), apiKey);
    }

    @Test
    void adminAuditAllowsApiKeyFilterAndOmitsNetwork() throws Exception {
        when(deliveries.getAdminPage(eq(0), eq(10), isNull(), any(), eq(999L))).thenReturn(Page.empty());
        mvc.perform(get("/api/admin/v1/bridge/delivery")
                .param("pageNumber", "0").param("pageSize", "10").param("apiKeyId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.network").doesNotExist())
                .andExpect(jsonPath("$.list").isEmpty());
        verify(deliveries).getAdminPage(0, 10, null, new DeliveryFilter(null, null, null, null, null, null), 999L);
    }

    @Test
    void adminSubscriptionsDefaultToActiveAndOmitNetwork() throws Exception {
        when(subscriptions.getAdminPage(0, 10, null, 999L, null, BridgeSubscriptionStatus.ACTIVE))
                .thenReturn(Page.empty());
        mvc.perform(get("/api/admin/v1/bridge/subscription")
                .param("pageNumber", "0").param("pageSize", "10").param("apiKeyId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.network").doesNotExist())
                .andExpect(jsonPath("$.list").isEmpty());
        verify(subscriptions).getAdminPage(0, 10, null, 999L, null, BridgeSubscriptionStatus.ACTIVE);
    }
}
