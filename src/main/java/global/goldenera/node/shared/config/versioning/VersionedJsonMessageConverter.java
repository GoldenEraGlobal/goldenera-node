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
import java.util.Map;

import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * A versioned JSON message converter that selects the appropriate ObjectMapper
 * based on the API version from the request URL (e.g., /v1/, /v2/).
 * 
 * <p>
 * This converter extends Spring's MappingJackson2HttpMessageConverter and only
 * overrides the write behavior to use version-specific ObjectMappers. All other
 * functionality (canRead, canWrite, read, etc.) is delegated to the parent
 * class.
 * 
 * <p>
 * <b>Key design decisions:</b>
 * <ul>
 * <li>Only handles versioned API requests (when ApiVersionContext has a version
 * set)</li>
 * <li>Falls back to default ObjectMapper for non-versioned requests</li>
 * <li>Does NOT interfere with Spring Boot's default converters for byte[],
 * Resource, etc.</li>
 * </ul>
 * 
 * <p>
 * <b>To add a new API version:</b>
 * <ol>
 * <li>Create a new ObjectMapper bean (e.g., jsonV2) in JacksonConfig</li>
 * <li>Add it to the versionedMappers in WebConfig</li>
 * </ol>
 */
public class VersionedJsonMessageConverter extends MappingJackson2HttpMessageConverter {

    private final Map<String, ObjectMapper> versionedMappers;
    private final ObjectMapper defaultMapper;

    /**
     * Creates a new VersionedJsonMessageConverter.
     * 
     * @param defaultMapper
     *            The default ObjectMapper (used when no version is set)
     * @param versionedMappers
     *            Map of version string (e.g., "v1") to ObjectMapper
     */
    public VersionedJsonMessageConverter(ObjectMapper defaultMapper, Map<String, ObjectMapper> versionedMappers) {
        super(defaultMapper);
        this.defaultMapper = defaultMapper;
        this.versionedMappers = versionedMappers;
    }

    /**
     * Only handle requests where API version is set.
     * This ensures OpenAPI, actuator, and other non-versioned endpoints
     * are handled by Spring Boot's default converter.
     */
    @Override
    public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
        // Only activate for versioned API requests
        if (ApiVersionContext.getVersion() == null) {
            return false;
        }
        return super.canWrite(clazz, mediaType);
    }

    /**
     * Only handle requests where API version is set (generic type variant).
     */
    @Override
    public boolean canWrite(@Nullable Type type, Class<?> clazz, @Nullable MediaType mediaType) {
        // Only activate for versioned API requests
        if (ApiVersionContext.getVersion() == null) {
            return false;
        }
        return super.canWrite(type, clazz, mediaType);
    }

    /**
     * Selects the appropriate ObjectMapper based on the current API version.
     */
    private ObjectMapper selectMapper() {
        String version = ApiVersionContext.getVersion();
        // At this point version should never be null (checked in canWrite),
        // but fallback to default just in case
        return versionedMappers.getOrDefault(version, defaultMapper);
    }

    @Override
    protected void writeInternal(Object object, @Nullable Type type, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {

        ObjectMapper mapper = selectMapper();
        MediaType contentType = outputMessage.getHeaders().getContentType();
        JsonEncoding encoding = getJsonEncoding(contentType);

        try (JsonGenerator generator = mapper.getFactory().createGenerator(outputMessage.getBody(), encoding)) {
            writePrefix(generator, object);

            JavaType javaType = resolveJavaType(mapper, type, object.getClass());
            ObjectWriter objectWriter = mapper.writerFor(javaType);

            // Handle SSE streaming
            if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
                objectWriter = objectWriter.with(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
            }

            objectWriter.writeValue(generator, object);

            writeSuffix(generator, object);
            generator.flush();
        } catch (JsonProcessingException ex) {
            throw new HttpMessageNotWritableException("Could not write JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * Resolves JavaType from the given type, falling back to the object's class.
     */
    private JavaType resolveJavaType(ObjectMapper mapper, @Nullable Type type, Class<?> clazz) {
        if (type != null) {
            return mapper.constructType(type);
        }
        return mapper.constructType(clazz);
    }
}
