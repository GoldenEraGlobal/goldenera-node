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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.properties.ApiKeyAuthenticationCacheProperties;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import global.goldenera.node.shared.services.core.ApiKeyAuthenticationCache;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ApiKeyAuthFilterTest {

	private static final Instant EXPIRY = Instant.parse("2026-08-31T12:00:00Z");

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void cachedKeyExpiresAtRequestTimeWithoutWaitingForCacheTtl() throws Exception {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey apiKey = apiKey(true, EXPIRY, ApiKeyPermission.READ_TX);
		when(repository.findByKeyPrefix(apiKey.getKeyPrefix())).thenReturn(Optional.of(apiKey));
		ApiKeyCoreService service = service(repository, Duration.ofMinutes(1));
		HmacComponent hmac = matchingHmac();

		MockHttpServletResponse beforeExpiry = authenticate(service, hmac, apiKey,
				Clock.fixed(EXPIRY.minusNanos(1), ZoneOffset.UTC));
		SecurityContextHolder.clearContext();
		MockHttpServletResponse afterExpiry = authenticate(service, hmac, apiKey,
				Clock.fixed(EXPIRY.plusNanos(1), ZoneOffset.UTC));

		assertThat(beforeExpiry.getStatus()).isEqualTo(200);
		assertThat(afterExpiry.getStatus()).isEqualTo(401);
		assertThat(afterExpiry.getContentAsString()).contains("API Key is expired");
		verify(repository).findByKeyPrefix(apiKey.getKeyPrefix());
	}

	@Test
	void cachedSnapshotPreservesApiKeyPrincipalAndPermissions() throws Exception {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey apiKey = apiKey(true, null, ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS);
		when(repository.findByKeyPrefix(apiKey.getKeyPrefix())).thenReturn(Optional.of(apiKey));
		ApiKeyCoreService service = service(repository, Duration.ofSeconds(5));

		MockHttpServletResponse response = authenticate(service, matchingHmac(), apiKey, Clock.systemUTC());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(ApiKey.class);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS.getAuthority());
	}

	@Test
	void disabledCachedKeyIsNeverAuthenticated() throws Exception {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey apiKey = apiKey(false, null, ApiKeyPermission.READ_TX);
		when(repository.findByKeyPrefix(apiKey.getKeyPrefix())).thenReturn(Optional.of(apiKey));
		ApiKeyCoreService service = service(repository, Duration.ofSeconds(5));

		MockHttpServletResponse first = authenticate(service, matchingHmac(), apiKey, Clock.systemUTC());
		MockHttpServletResponse second = authenticate(service, matchingHmac(), apiKey, Clock.systemUTC());

		assertThat(first.getContentAsString()).contains("API Key is disabled");
		assertThat(second.getContentAsString()).contains("API Key is disabled");
		verify(repository).findByKeyPrefix(apiKey.getKeyPrefix());
	}

	@Test
	void rejectsOversizedCredentialBeforeAnyDatabaseLookup() throws Exception {
		ApiKeyCoreService service = mock(ApiKeyCoreService.class);
		ApiKeyAuthFilter filter = new ApiKeyAuthFilter(service, mock(HmacComponent.class), new ObjectMapper());
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-API-Key", "sk_" + "x".repeat(ApiKeyAuthFilter.MAX_CREDENTIAL_LENGTH));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilterInternal(request, response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(401);
		verify(service, never()).withAuthenticationKey(any(), any());
	}

	private static ApiKeyCoreService service(ApiKeyCoreRepository repository, Duration ttl) {
		ApiKeyAuthenticationCacheProperties properties = new ApiKeyAuthenticationCacheProperties();
		properties.setTtl(ttl);
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(repository, properties,
				new SimpleMeterRegistry());
		return new ApiKeyCoreService(repository, mock(ApplicationEventPublisher.class), cache);
	}

	private static HmacComponent matchingHmac() {
		HmacComponent hmac = mock(HmacComponent.class);
		when(hmac.hash(any(Bytes.class))).thenReturn(Bytes.of(1, 2, 3));
		when(hmac.secureCompare(any(Bytes.class), any(Bytes.class)))
				.thenAnswer(invocation -> invocation.<Bytes>getArgument(0).equals(invocation.getArgument(1)));
		return hmac;
	}

	private static MockHttpServletResponse authenticate(ApiKeyCoreService service, HmacComponent hmac,
			ApiKey apiKey, Clock clock) throws Exception {
		ApiKeyAuthFilter filter = new ApiKeyAuthFilter(service, hmac, new ObjectMapper(), clock);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-API-Key", apiKey.getKeyPrefix() + "_secret");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request, response, new MockFilterChain());
		return response;
	}

	private static ApiKey apiKey(boolean enabled, Instant expiresAt, ApiKeyPermission permission) {
		ApiKey apiKey = new ApiKey();
		apiKey.setId(1L);
		apiKey.setVersion(1L);
		apiKey.setLabel("test-key");
		apiKey.setKeyPrefix("sk_12345678901");
		apiKey.setSecretKey(Bytes.of(1, 2, 3));
		apiKey.setWebhookSecretKey(Bytes.of(4, 5, 6));
		apiKey.setEnabled(enabled);
		apiKey.setMaxWebhooks(10L);
		apiKey.setExpiresAt(expiresAt);
		apiKey.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		apiKey.setPermissions(Set.of(permission));
		return apiKey;
	}
}
