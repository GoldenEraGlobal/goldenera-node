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

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.config.versioning.ApiVersionInterceptor;
import global.goldenera.node.shared.config.versioning.VersionAwareHttpMessageConverter;
import global.goldenera.node.shared.converters.ReflectionEnumConverter;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.experimental.FieldDefaults;

@Configuration
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebConfig implements WebMvcConfigurer {

    ObjectMapper objectMapperV1;

    public WebConfig(@Qualifier("jsonV1") ObjectMapper objectMapperV1) {
        this.objectMapperV1 = objectMapperV1;
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

    @Bean
    public VersionAwareHttpMessageConverter versionAwareHttpMessageConverter() {
        return new VersionAwareHttpMessageConverter(objectMapperV1);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiVersionInterceptor());
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(converter -> converter instanceof MappingJackson2HttpMessageConverter);
        converters.add(versionAwareHttpMessageConverter());
    }
}