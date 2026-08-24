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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import global.goldenera.node.shared.services.ThrottlingService;

class ThrottlingFilterTest {

	private static final String SNAPSHOT_MANIFEST_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/manifest";

	@Test
	void bridgeRequestsAreSubjectToSpecificApiRateLimit() throws Exception {
		ThrottlingService service = mock(ThrottlingService.class);
		when(service.checkGlobalIpLimit(any())).thenReturn(true);
		when(service.checkSpecificLimit(any(), eq("127.0.0.1"), eq(false))).thenReturn(true);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bridge/v1/block/last");
		request.setRemoteAddr("127.0.0.1");

		new ThrottlingFilter(service).doFilter(
				request,
				new MockHttpServletResponse(),
				new MockFilterChain());

		verify(service).checkSpecificLimit(request, "127.0.0.1", false);
	}

	@Test
	void snapshotRequestsBypassGenericRateLimitsAndUseTheirDedicatedStreamLimiter() throws Exception {
		ThrottlingService service = mock(ThrottlingService.class);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", SNAPSHOT_MANIFEST_PATH);
		request.setRemoteAddr("127.0.0.1");
		MockFilterChain chain = mock(MockFilterChain.class);

		new ThrottlingFilter(service).doFilter(
				request,
				new MockHttpServletResponse(),
				chain);

		verify(service, never()).checkGlobalIpLimit(any());
		verify(service, never()).checkSpecificLimit(any(), any(), eq(false));
		verify(chain).doFilter(any(), any());
	}

	@Test
	void versionedSnapshotChunksAlsoBypassGenericRateLimits() throws Exception {
		ThrottlingService service = mock(ThrottlingService.class);
		MockHttpServletRequest request = new MockHttpServletRequest(
				"GET",
				"/api/core/v1/sync/snapshots/checkpoint/versions/snapshot-1-hash/archive/chunks/0");
		request.setRemoteAddr("127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = mock(MockFilterChain.class);

		new ThrottlingFilter(service).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(service, never()).checkGlobalIpLimit(any());
		verify(chain).doFilter(any(), any());
	}

	@Test
	void nonSnapshotCoreRequestStillReturnsRateLimitResponse() throws Exception {
		ThrottlingService service = mock(ThrottlingService.class);
		when(service.checkGlobalIpLimit(any())).thenReturn(true);
		when(service.checkSpecificLimit(any(), eq("127.0.0.1"), eq(false))).thenReturn(false);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/core/v1/node/info");
		request.setRemoteAddr("127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = mock(MockFilterChain.class);

		new ThrottlingFilter(service).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("1");
		verify(chain, never()).doFilter(any(), any());
	}
}
