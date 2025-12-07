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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.config.versioning.ApiVersionInterceptor;
import global.goldenera.node.shared.config.versioning.VersionedJsonMessageConverter;
import global.goldenera.node.shared.converters.ReflectionEnumConverter;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.experimental.FieldDefaults;

@Configuration
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebConfig implements WebMvcConfigurer {

    ObjectMapper baseObjectMapper;
    ObjectMapper objectMapperV1;
    // Future: ObjectMapper objectMapperV2;

    public WebConfig(
            ObjectMapper baseObjectMapper,
            @Qualifier("jsonV1") ObjectMapper objectMapperV1
    // Future: @Qualifier("jsonV2") ObjectMapper objectMapperV2
    ) {
        this.baseObjectMapper = baseObjectMapper;
        this.objectMapperV1 = objectMapperV1;
        // Future: this.objectMapperV2 = objectMapperV2;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addFormatters(FormatterRegistry registry) {

        // 1. ENUMS
        for (Class<? extends Enum<?>> enumClass : GlobalTypeRegistry.CODE_ENUMS) {
            registry.addConverter(String.class, enumClass, new ReflectionEnumConverter(enumClass));
        }

        // 2. CUSTOM TYPES
        for (GlobalTypeRegistry.StringTypeAdapter<?> adapter : GlobalTypeRegistry.STRING_ADAPTERS) {
            registerStringAdapter(registry, adapter);
        }
    }

    private <T> void registerStringAdapter(FormatterRegistry registry,
            GlobalTypeRegistry.StringTypeAdapter<T> adapter) {
        registry.addConverter(String.class, adapter.getType(), new Converter<String, T>() {
            @Override
            public T convert(String source) {
                if (source == null || source.trim().isEmpty()) {
                    return null;
                }
                try {
                    return adapter.getFromString().apply(source);
                } catch (Exception e) {
                    throw new GEValidationException(
                            String.format("Invalid %s value: '%s'",
                                    adapter.getType().getSimpleName(), source));
                }
            }
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiVersionInterceptor());
    }

    /**
     * Extends (not replaces!) Spring Boot's default message converters.
     * 
     * <p>
     * Our versioned converter is added at the FRONT of the list (highest priority).
     * It only accepts requests where ApiVersionContext has a version set.
     * For all other requests (OpenAPI, actuator, etc.), it returns false in
     * canWrite()
     * and the request falls through to Spring Boot's default
     * MappingJackson2HttpMessageConverter.
     * 
     * <p>
     * IMPORTANT: We do NOT remove the default converter - it handles non-versioned
     * endpoints.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // Build version -> ObjectMapper registry
        // To add v2: just add another entry here
        Map<String, ObjectMapper> versionedMappers = new HashMap<>();
        versionedMappers.put("v1", objectMapperV1);
        // Future: versionedMappers.put("v2", objectMapperV2);

        // Create our versioned converter
        VersionedJsonMessageConverter versionedConverter = new VersionedJsonMessageConverter(baseObjectMapper,
                versionedMappers);

        // Add our versioned converter at the front (high priority)
        // It will only handle versioned API requests (canWrite checks
        // ApiVersionContext)
        // Non-versioned requests fall through to Spring Boot's default converter
        converters.add(0, versionedConverter);
    }
}