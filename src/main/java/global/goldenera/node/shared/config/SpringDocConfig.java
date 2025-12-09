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
package global.goldenera.node.shared.config;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Configuration
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class SpringDocConfig {

	static {
		for (GlobalTypeRegistry.StringTypeAdapter<?> adapter : GlobalTypeRegistry.STRING_ADAPTERS) {
			SpringDocUtils.getConfig().replaceWithClass(adapter.getType(), String.class);
		}
		SpringDocUtils.getConfig().replaceWithClass(BigDecimal.class, String.class);
		SpringDocUtils.getConfig().replaceWithClass(BigInteger.class, String.class);
	}

	static String API_KEY_SCHEME = "ApiKeyAuth";
	static String BASIC_AUTH_SCHEME = "BasicAuth";

	@Bean
	public OpenAPI customOpenAPI() {
		return new OpenAPI()
				.components(new Components()
						.addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name("X-API-Key")
								.description("API Key for explorer access"))
						.addSecuritySchemes(BASIC_AUTH_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("basic")
								.description("Basic Auth for Node Admins")));
	}

	// --- API GROUPS ---
	@Bean
	public GroupedOpenApi coreApi() {
		return GroupedOpenApi.builder().group("CORE API").pathsToMatch("/api/core/**")
				.addOperationCustomizer((op, m) -> {
					op.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
					return op;
				}).build();
	}

	@Bean
	public GroupedOpenApi sharedApi() {
		return GroupedOpenApi.builder().group("Shared API").pathsToMatch("/api/shared/**")
				.addOperationCustomizer((op, m) -> {
					op.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
					return op;
				}).build();
	}

	@Bean
	public GroupedOpenApi explorerApi() {
		return GroupedOpenApi.builder().group("Explorer API").pathsToMatch("/api/explorer/**")
				.addOperationCustomizer((op, m) -> {
					op.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
					return op;
				}).build();
	}

	@Bean
	public GroupedOpenApi nodeApi() {
		return GroupedOpenApi.builder().group("Node Admin API").pathsToMatch("/api/admin/**")
				.addOperationCustomizer((op, m) -> {
					op.addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH_SCHEME));
					return op;
				}).build();
	}
}