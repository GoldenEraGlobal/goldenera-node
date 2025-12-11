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

import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	ApiKeyCoreService apiKeyCoreService;
	HmacComponent hmacComponent;
	ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain)
			throws ServletException, IOException {
		String apiKeyHeader = request.getHeader("X-API-Key");
		if (apiKeyHeader == null) {
			apiKeyHeader = request.getParameter("Authorization");
		}
		if (apiKeyHeader == null) {
			filterChain.doFilter(request, response);
			return;
		}
		apiKeyHeader = apiKeyHeader.trim().replace("Bearer ", "");
		if (apiKeyHeader.isBlank() || !apiKeyHeader.startsWith("sk_")) {
			sendError(response, "Invalid API Key format");
			return;
		}

		String prefix;
		String secret;

		// Standard format: sk_ + 11 chars (8 bytes base64) + _ + secret
		// Prefix length = 14
		// We check if the key matches the standard length structure to avoid ambiguity
		// with underscores in prefix/secret
		if (apiKeyHeader.length() > 15 && apiKeyHeader.charAt(14) == '_') {
			prefix = apiKeyHeader.substring(0, 14);
			secret = apiKeyHeader.substring(15);
		} else {
			// Fallback logic: split by the second underscore
			// This is risky if the prefix contains underscores, but necessary for
			// legacy/custom keys
			int firstUnderscore = apiKeyHeader.indexOf('_');
			if (firstUnderscore == -1) {
				sendError(response, "Invalid API Key format");
				return;
			}

			int separatorIndex = apiKeyHeader.indexOf('_', firstUnderscore + 1);
			if (separatorIndex == -1) {
				sendError(response, "Invalid API Key format");
				return;
			}

			prefix = apiKeyHeader.substring(0, separatorIndex);
			secret = apiKeyHeader.substring(separatorIndex + 1);
		}

		if (secret.isBlank()) {
			sendError(response, "Invalid API Key format, secret part is blank");
			return;
		}

		Optional<ApiKey> apiKeyOpt = apiKeyCoreService.getByKeyPrefixOptional(prefix);
		if (apiKeyOpt.isEmpty()) {
			sendError(response, "Unknown API Key");
			return;
		}

		ApiKey apiKey = apiKeyOpt.get();
		if (!apiKey.isEnabled()) {
			sendError(response, "API Key is disabled");
			return;
		}

		if (apiKey.isExpired()) {
			sendError(response, "API Key is expired");
			return;
		}

		Bytes secretBytes = Bytes.wrap(secret.getBytes(StandardCharsets.UTF_8));
		Bytes hashedSecret = hmacComponent.hash(secretBytes);
		if (!hmacComponent.secureCompare(hashedSecret, apiKey.getSecretKey())) {
			sendError(response, "Invalid API Key");
			return;
		}

		List<SimpleGrantedAuthority> authorities = apiKey.getPermissions().stream()
				.map(permission -> new SimpleGrantedAuthority(permission.getAuthority()))
				.collect(Collectors.toList());

		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
				apiKey,
				null,
				authorities);

		SecurityContextHolder.getContext().setAuthentication(authentication);
		filterChain.doFilter(request, response);
	}

	private void sendError(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);

		Map<String, String> errorResponse = Map.of(
				"status", "401",
				"error", "Unauthorized",
				"message", message);

		response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
	}
}