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
package global.goldenera.node.shared.config.versioning;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A Proxy Converter implementing GenericHttpMessageConverter directly.
 * This bypasses the 'final' restriction of AbstractGenericHttpMessageConverter
 * and delegates full processing to the underlying Spring converters.
 */
public class VersionAwareHttpMessageConverter implements GenericHttpMessageConverter<Object> {

    private final MappingJackson2HttpMessageConverter delegateV1;
    private final List<MediaType> supportedMediaTypes;

    public VersionAwareHttpMessageConverter(ObjectMapper mapperV1) {
        // Create real Spring converters
        this.delegateV1 = new MappingJackson2HttpMessageConverter(mapperV1);

        // Define supported types (JSON)
        this.supportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON);

        // Sync media types to delegates just in case
        this.delegateV1.setSupportedMediaTypes(this.supportedMediaTypes);
    }

    // --- HELPER TO CHOOSE DELEGATE ---
    private MappingJackson2HttpMessageConverter getDelegate() {
        // String version = ApiVersionContext.getVersion();
        return delegateV1;
    }

    // --- WRITE (Serialization) ---

    @Override
    public void write(Object t, @Nullable Type type, @Nullable MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        getDelegate().write(t, type, contentType, outputMessage);
    }

    // This is the method from HttpMessageConverter interface (non-generic)
    @Override
    public void write(Object t, @Nullable MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        getDelegate().write(t, contentType, outputMessage);
    }

    // --- READ (Deserialization) ---

    @Override
    public Object read(Type type, @Nullable Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return getDelegate().read(type, contextClass, inputMessage);
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return getDelegate().read(clazz, inputMessage);
    }

    // --- CAPABILITIES CHECKS ---

    @Override
    public boolean canWrite(@Nullable Type type, Class<?> clazz, @Nullable MediaType mediaType) {
        return getDelegate().canWrite(type, clazz, mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
        return getDelegate().canWrite(clazz, mediaType);
    }

    @Override
    public boolean canRead(Type type, @Nullable Class<?> contextClass, @Nullable MediaType mediaType) {
        return getDelegate().canRead(type, contextClass, mediaType);
    }

    @Override
    public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
        return getDelegate().canRead(clazz, mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return supportedMediaTypes;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
        return getDelegate().getSupportedMediaTypes(clazz);
    }
}