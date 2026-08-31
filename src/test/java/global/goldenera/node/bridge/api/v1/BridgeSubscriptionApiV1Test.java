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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeUnsubscribeDtoV1;
import global.goldenera.node.bridge.api.v1.mappers.BridgeSubscriptionMapper;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.services.BridgeAddressService;
import global.goldenera.node.bridge.services.BridgeSubscriptionService;
import global.goldenera.node.shared.config.ExceptionHandlerConfig;
import global.goldenera.node.shared.entities.ApiKey;

class BridgeSubscriptionApiV1Test {

    private final BridgeAddressService service = mock(BridgeAddressService.class);
    private final BridgeSubscriptionService subscriptions = mock(BridgeSubscriptionService.class);
    private final ApiKey apiKey = mock(ApiKey.class);
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(apiKey, null, List.of());
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new BridgeSubscriptionApiV1(
                service, subscriptions, mock(BridgeSubscriptionMapper.class), new ObjectMapper()))
                .setControllerAdvice(new ExceptionHandlerConfig(new ObjectMapper())).build();
    }

    @Test
    void explicitNullDeletesAllAndReturnsNetworkWithoutRequestNetwork() throws Exception {
        when(service.unsubscribeBatch(isNull(), eq(apiKey))).thenReturn(new BridgeUnsubscribeDtoV1(Network.MAINNET, 4));
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.network").value("MAINNET"))
                .andExpect(jsonPath("$.unsubscribedCount").value(4));
        verify(service).unsubscribeBatch(null, apiKey);
    }

    @Test
    void emptyArrayRemainsAnEmptyArray() throws Exception {
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        verify(service).unsubscribeBatch(List.of(), apiKey);
    }

    @Test
    void allowsWhitespaceAroundExplicitNullAndJsonSuffixMediaTypes() throws Exception {
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType("application/vnd.goldenera+json").content(" \n null \t "))
                .andExpect(status().isOk());
        verify(service).unsubscribeBatch(null, apiKey);
    }

    @Test
    void rejectsNonJsonContentTypesWithoutDeletingAnything() throws Exception {
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType(MediaType.TEXT_PLAIN).content("null"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void arrayIsPassedWithAuthenticatedApiKey() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType(MediaType.APPLICATION_JSON).content("[\"" + id + "\"]")).andExpect(status().isOk());
        verify(service).unsubscribeBatch(List.of(id), apiKey);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "{}", "[null]", "[1]", "\"null\"", "[\"invalid\"]", "[\"1-1-1-1-1\"]",
            "null []", "null false", "null invalid", "[] null" })
    void rejectsMissingAndInvalidBodiesWithoutDeletingAnything(String body) throws Exception {
        mvc.perform(delete("/api/bridge/v1/address/subscription").principal(authentication)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void pageDefaultsToActiveAndIgnoresUntrustedApiKeyId() throws Exception {
        mvc.perform(get("/api/bridge/v1/address/subscription/page").principal(authentication)
                .param("pageNumber", "0").param("pageSize", "10").param("apiKeyId", "999"))
                .andExpect(status().isOk());
        verify(subscriptions).getPage(0, 10, null, null, BridgeSubscriptionStatus.ACTIVE, apiKey);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ACTIVE", "INACTIVE", "ALL" })
    void supportsAllSubscriptionStatuses(String status) throws Exception {
        mvc.perform(get("/api/bridge/v1/address/subscription/page").principal(authentication)
                .param("pageNumber", "0").param("pageSize", "10").param("status", status))
                .andExpect(status().isOk());
        verify(subscriptions).getPage(0, 10, null, null, BridgeSubscriptionStatus.valueOf(status), apiKey);
    }
}
