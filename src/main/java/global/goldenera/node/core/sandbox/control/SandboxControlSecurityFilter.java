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
package global.goldenera.node.core.sandbox.control;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.sandbox.control.SandboxControlAuditLog.Action;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Error;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

final class SandboxControlSecurityFilter extends OncePerRequestFilter {

	static final int MAX_REQUEST_BODY_BYTES = 4096;
	static final int MAX_CONCURRENT_REQUESTS = 4;
	private static final String ROUTE_PREFIX = "/api/sandbox/v1/control";
	private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH");
	private static final Set<String> QUERY_CREDENTIAL_NAMES = Set.of(
			"authorization", "access_token", "token", "api_key", "x-api-key");
	private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.build());

	private final SandboxControlTokenAuthenticator authenticator;
	private final SandboxControlAuditLog auditLog;
	private final Bucket authenticatedRateBucket;
	private final Bucket authenticationFailureRateBucket;
	private final ObjectMapper objectMapper;
	private final Semaphore requestPermits = new Semaphore(MAX_CONCURRENT_REQUESTS);

	SandboxControlSecurityFilter(
			SandboxControlTokenAuthenticator authenticator,
			SandboxControlAuditLog auditLog,
			Bucket authenticatedRateBucket,
			Bucket authenticationFailureRateBucket,
			ObjectMapper objectMapper) {
		this.authenticator = authenticator;
		this.auditLog = auditLog;
		this.authenticatedRateBucket = authenticatedRateBucket;
		this.authenticationFailureRateBucket = authenticationFailureRateBucket;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(ROUTE_PREFIX);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		if (!requestPermits.tryAcquire()) {
			reject(response, HttpStatus.TOO_MANY_REQUESTS.value(),
					"CONCURRENCY_LIMITED", "Too many sandbox control requests are active", Action.RATE_LIMIT);
			response.setHeader("Retry-After", "1");
			return;
		}
		try {
			filterWithPermit(request, response, filterChain);
		} finally {
			requestPermits.release();
		}
	}

	private void filterWithPermit(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
			reject(response, HttpStatus.PAYLOAD_TOO_LARGE.value(),
					"REQUEST_BODY_TOO_LARGE", "JSON request body exceeds 4096 bytes", Action.REQUEST_REJECTED);
			return;
		}
		String bearer = bearerToken(request);
		if (bearer == null || !authenticator.authenticate(bearer)) {
			if (!authenticationFailureRateBucket.tryConsume(1)) {
				reject(response, HttpStatus.TOO_MANY_REQUESTS.value(),
						"AUTH_RATE_LIMITED", "Bearer authentication failure rate exceeded", Action.RATE_LIMIT);
				response.setHeader("Retry-After", "1");
				return;
			}
			reject(response, HttpServletResponse.SC_UNAUTHORIZED,
					"UNAUTHORIZED", "Bearer authentication is required", Action.AUTHENTICATE);
			return;
		}
		if (!authenticatedRateBucket.tryConsume(1)) {
			reject(response, HttpStatus.TOO_MANY_REQUESTS.value(),
					"RATE_LIMITED", "Sandbox control request rate exceeded", Action.RATE_LIMIT);
			response.setHeader("Retry-After", "1");
			return;
		}

		UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
				"sandbox-control",
				null,
				List.of(new SimpleGrantedAuthority("ROLE_SANDBOX_CONTROL")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		auditLog.record(Action.AUTHENTICATE, "ACCEPTED", null);

		HttpServletRequest guardedRequest = request;
		if (MUTATION_METHODS.contains(request.getMethod())) {
			if (!isJson(request.getContentType())) {
				reject(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
						"UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", Action.REQUEST_REJECTED);
				return;
			}
			byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
			if (body.length > MAX_REQUEST_BODY_BYTES) {
				reject(response, HttpStatus.PAYLOAD_TOO_LARGE.value(),
						"REQUEST_BODY_TOO_LARGE", "JSON request body exceeds 4096 bytes", Action.REQUEST_REJECTED);
				return;
			}
			if (!hasStrictJsonShape(request.getRequestURI(), body)) {
				reject(response, HttpServletResponse.SC_BAD_REQUEST,
						"INVALID_JSON", "Request body must be strict JSON", Action.REQUEST_REJECTED);
				return;
			}
			guardedRequest = new CachedBodyRequest(request, body);
		}
		filterChain.doFilter(guardedRequest, response);
	}

	private String bearerToken(HttpServletRequest request) {
		if (request.getHeader("X-API-Key") != null || request.getHeader("Cookie") != null) {
			return null;
		}
		Enumeration<String> parameterNames = request.getParameterNames();
		while (parameterNames.hasMoreElements()) {
			if (QUERY_CREDENTIAL_NAMES.contains(parameterNames.nextElement().toLowerCase(Locale.ROOT))) {
				return null;
			}
		}

		Enumeration<String> values = request.getHeaders("Authorization");
		if (values == null || !values.hasMoreElements()) {
			return null;
		}
		String value = values.nextElement();
		if (values.hasMoreElements() || value == null || value.indexOf(',') >= 0 || !value.startsWith("Bearer ")) {
			return null;
		}
		String token = value.substring("Bearer ".length());
		return token.length() == 43 && token.indexOf(' ') < 0 ? token : null;
	}

	private boolean isJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private boolean hasStrictJsonShape(String requestUri, byte[] body) {
		Set<String> allowedFields;
		if (requestUri.endsWith("/exact-one")) {
			allowedFields = Set.of("scheduledTimestamp", "deadlineMs");
		} else if (requestUri.endsWith("/autonomous")) {
			allowedFields = Set.of("enabled");
		} else {
			allowedFields = null;
		}
		try (JsonParser parser = STRICT_JSON.createParser(body)) {
			JsonNode root = STRICT_JSON.readTree(parser);
			if (parser.nextToken() != null) {
				return false;
			}
			if (root == null || !root.isObject()) {
				return false;
			}
			if (allowedFields == null) {
				return true;
			}
			Iterator<String> fields = root.fieldNames();
			while (fields.hasNext()) {
				if (!allowedFields.contains(fields.next())) {
					return false;
				}
			}
			if (requestUri.endsWith("/exact-one")) {
				JsonNode deadline = root.get("deadlineMs");
				if (deadline == null || !deadline.isIntegralNumber() || !deadline.canConvertToLong()) {
					return false;
				}
				JsonNode timestamp = root.get("scheduledTimestamp");
				return timestamp == null || timestamp.isNull() || timestamp.isTextual();
			}
			if (requestUri.endsWith("/autonomous")) {
				JsonNode enabled = root.get("enabled");
				return enabled != null && enabled.isBoolean();
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void reject(
			HttpServletResponse response,
			int status,
			String code,
			String message,
			Action action) throws IOException {
		auditLog.record(action, code, null);
		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new Error(code, message));
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream input = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return input.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener readListener) {
					if (readListener == null) {
						throw new IllegalArgumentException("readListener cannot be null");
					}
				}

				@Override
				public int read() {
					return input.read();
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}
}
