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
package global.goldenera.node.shared.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.properties.ApiKeyAuthenticationCacheProperties;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import global.goldenera.node.shared.services.ThrottlingService;
import global.goldenera.node.shared.services.core.ApiKeyAuthenticationCache;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PreAuthenticationThrottleFilterTest {

	@Test
	void randomizedUnknownPrefixesStopReachingDatabaseAfterGlobalLimit() throws Exception {
		ThrottlingService throttling = mock(ThrottlingService.class);
		AtomicInteger allowed = new AtomicInteger();
		when(throttling.checkGlobalIpLimit(any())).thenAnswer(ignored -> allowed.getAndIncrement() < 3);
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		when(repository.findAuthenticationEpoch()).thenReturn(0L);
		when(repository.findByKeyPrefix(any())).thenReturn(Optional.empty());
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(repository,
				new ApiKeyAuthenticationCacheProperties(), new SimpleMeterRegistry());
		ApiKeyCoreService coreService = new ApiKeyCoreService(
				repository, mock(ApplicationEventPublisher.class), cache);
		ApiKeyAuthFilter authentication = new ApiKeyAuthFilter(
				coreService, mock(HmacComponent.class), new ObjectMapper());
		PreAuthenticationThrottleFilter preAuthentication = new PreAuthenticationThrottleFilter(throttling);

		for (int attempt = 0; attempt < 20; attempt++) {
			String randomPrefix = "sk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shared/v1/probe");
			request.setRemoteAddr("192.0.2.10");
			request.addHeader("X-API-Key", randomPrefix + "_secret");
			MockHttpServletResponse response = new MockHttpServletResponse();
			preAuthentication.doFilter(request, response,
					(req, res) -> authentication.doFilter(req, res, new MockFilterChain()));
		}

		verify(repository, times(3)).findAuthenticationEpoch();
		verify(repository, times(3)).findByKeyPrefix(any());
	}

	@Test
	void postAuthenticationFilterDoesNotChargeGlobalBucketTwice() throws Exception {
		ThrottlingService throttling = mock(ThrottlingService.class);
		when(throttling.checkGlobalIpLimit(any())).thenReturn(true);
		when(throttling.checkSpecificLimit(any(), any(), anyBoolean())).thenReturn(true);
		PreAuthenticationThrottleFilter preAuthentication = new PreAuthenticationThrottleFilter(throttling);
		ThrottlingFilter postAuthentication = new ThrottlingFilter(throttling);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/core/v1/node/info");
		request.setRemoteAddr("192.0.2.11");

		preAuthentication.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> postAuthentication.doFilter(req, res, new MockFilterChain()));

		verify(throttling).checkGlobalIpLimit(request);
		verify(throttling).checkSpecificLimit(any(), any(), anyBoolean());
	}

	@Test
	void unauthenticatedSnapshotStillBypassesGenericGlobalLimit() throws Exception {
		ThrottlingService throttling = mock(ThrottlingService.class);
		PreAuthenticationThrottleFilter filter = new PreAuthenticationThrottleFilter(throttling);
		MockHttpServletRequest request = new MockHttpServletRequest(
				"GET", "/api/core/v1/sync/snapshots/checkpoint/manifest");
		MockFilterChain chain = mock(MockFilterChain.class);

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(throttling, never()).checkGlobalIpLimit(any());
		verify(chain).doFilter(any(), any());
	}
}
