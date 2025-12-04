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
import java.util.Iterator;

import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.JavaType;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
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

	// --- ENUM DOC GENERATOR ---
	@Bean
	public ModelConverter enumSchemaConverter() {
		return new ModelConverter() {
			@Override
			public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
				JavaType javaType = Json.mapper().constructType(type.getType());
				if (javaType != null && javaType.isEnumType()) {
					Class<?> cls = javaType.getRawClass();
					if (GlobalTypeRegistry.CODE_ENUMS.contains(cls)) {
						return createEnumSchema(cls);
					}
				}
				if (chain.hasNext())
					return chain.next().resolve(type, context, chain);
				return null;
			}
		};
	}

	private Schema createEnumSchema(Class<?> cls) {
		IntegerSchema schema = new IntegerSchema();
		Object[] constants = cls.getEnumConstants();
		StringBuilder description = new StringBuilder();
		description.append("<b>Enum Values:</b><br>");
		description.append("<table border=\"1\" style=\"border-collapse: collapse;\">");
		description.append(
				"<thead><tr><th style=\"padding: 4px;\">Code</th><th style=\"padding: 4px;\">Name</th></tr></thead>");
		description.append("<tbody>");

		try {
			for (Object obj : constants) {
				int code = (int) cls.getMethod("getCode").invoke(obj);
				String name = ((Enum<?>) obj).name();

				description.append(String.format(
						"<tr><td style=\"padding: 4px;\"><code>%d</code></td><td style=\"padding: 4px;\">%s</td></tr>",
						code, name));
			}
		} catch (Exception e) {
		}

		description.append("</tbody></table>");

		schema.setDescription(description.toString());

		try {
			int firstCode = (int) cls.getMethod("getCode").invoke(constants[0]);
			schema.setExample(firstCode);
		} catch (Exception e) {
		}

		return schema;
	}
}