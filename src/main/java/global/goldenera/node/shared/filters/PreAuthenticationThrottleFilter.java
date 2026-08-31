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

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import global.goldenera.node.shared.services.ThrottlingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Applies the global per-IP limit before any credential can reach PostgreSQL. */
public class PreAuthenticationThrottleFilter extends OncePerRequestFilter {

	static final String GLOBAL_LIMIT_CHECKED_ATTRIBUTE =
			PreAuthenticationThrottleFilter.class.getName() + ".globalLimitChecked";
	private static final String SNAPSHOT_PATH_PREFIX = "/api/core/v1/sync/snapshots/checkpoint/";

	private final ThrottlingService throttlingService;

	public PreAuthenticationThrottleFilter(ThrottlingService throttlingService) {
		this.throttlingService = throttlingService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith(SNAPSHOT_PATH_PREFIX)
				&& request.getHeader("X-API-Key") == null
				&& request.getParameter("Authorization") == null;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!throttlingService.checkGlobalIpLimit(request)) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setContentType("application/json");
			response.addHeader("Retry-After", "1");
			response.getWriter().write(
					"{\"error\": \"Global Rate Limit Exceeded. You are sending too many requests.\"}");
			return;
		}
		request.setAttribute(GLOBAL_LIMIT_CHECKED_ATTRIBUTE, Boolean.TRUE);
		filterChain.doFilter(request, response);
	}
}
