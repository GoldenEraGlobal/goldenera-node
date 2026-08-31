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
package global.goldenera.node.explorer.storage.chainidentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class ExplorerReadinessFilterTest {

	@Test
	void nonReadyExplorerReturnsStable503BeforeControllerChain() throws Exception {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.failed(ExplorerReadinessState.IDENTITY_MISMATCH, "wrong chain");
		ExplorerReadinessFilter filter = new ExplorerReadinessFilter(readiness);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/explorer/v1/tx");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getContentAsString()).isEqualTo(
				"{\"status\":503,\"error\":\"Service Unavailable\","
						+ "\"code\":\"EXPLORER_NOT_READY\",\"state\":\"IDENTITY_MISMATCH\"}");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void readyExplorerAndNonExplorerRequestsContinue() throws Exception {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerReadinessFilter filter = new ExplorerReadinessFilter(readiness);
		FilterChain chain = mock(FilterChain.class);
		MockHttpServletRequest coreRequest = new MockHttpServletRequest("GET", "/api/core/v1/node-info");
		MockHttpServletResponse coreResponse = new MockHttpServletResponse();

		filter.doFilter(coreRequest, coreResponse, chain);
		verify(chain).doFilter(coreRequest, coreResponse);

		readiness.ready();
		MockHttpServletRequest explorerRequest = new MockHttpServletRequest("GET", "/api/explorer/v1/tx");
		MockHttpServletResponse explorerResponse = new MockHttpServletResponse();
		filter.doFilter(explorerRequest, explorerResponse, chain);
		verify(chain).doFilter(explorerRequest, explorerResponse);
	}
}
