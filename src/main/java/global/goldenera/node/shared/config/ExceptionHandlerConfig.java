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

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.exceptions.CryptoJException;
import global.goldenera.cryptoj.exceptions.CryptoJFailedException;
import global.goldenera.cryptoj.exceptions.CryptoJRuntimeException;
import global.goldenera.node.shared.exceptions.GEAuthenticationException;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
public class ExceptionHandlerConfig {

    ObjectMapper objectMapper;

    // region Custom Exceptions

    @ResponseBody
    @ExceptionHandler(CryptoJFailedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleCryptoJFailedException(@NonNull CryptoJFailedException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(CryptoJRuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String handleCryptoJRuntimeException(@NonNull CryptoJRuntimeException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(CryptoJException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String handleCryptoJException(@NonNull CryptoJException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(GEAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String handleGEAuthenticationException(@NonNull GEAuthenticationException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String handleAuthorizationDeniedException(@NonNull AuthorizationDeniedException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(GENotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String handleGENotFoundException(@NonNull GENotFoundException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(GEValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleGEValidationException(@NonNull GEValidationException ex) {
        if (ex.getDetails() != null) {
            return wrapToJson(ex.getMessage(), ex.getDetails());
        }
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(GEFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String handleGEFailedException(@NonNull GEFailedException ex) {
        log.error("Unexpected internal server exception: {}", ex.getMessage(), ex);
        return wrapToJson("Unexpected internal server exception.");
    }

    // endregion

    // region Other Exceptions

    @ResponseBody
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleIllegalArgumentException(@NonNull IllegalArgumentException ex) {
        return wrapToJson(ex.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleMethodArgumentTypeMismatchException(@NonNull MethodArgumentTypeMismatchException ex) {
        log.error("Method argument type mismatch exception: {}", ex.getMessage(), ex);
        return wrapToJson(MethodArgumentTypeMismatchException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleHttpMessageNotReadableException(@NonNull HttpMessageNotReadableException ex) {
        log.error("HTTP message not readable exception: {}", ex.getMessage(), ex);
        return wrapToJson(HttpMessageNotReadableException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(JsonMappingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleJsonMappingException(@NonNull JsonMappingException ex) {
        log.error("JSON mapping exception: {}", ex.getMessage(), ex);
        return wrapToJson(JsonMappingException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleMissingServletRequestParameterException(@NonNull MissingServletRequestParameterException ex) {
        log.error("Missing servlet request parameter exception: {}", ex.getMessage(), ex);
        return wrapToJson(MissingServletRequestParameterException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(DateTimeParseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleDateTimeParseException(@NonNull DateTimeParseException ex) {
        return wrapToJson(DateTimeParseException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(RequestRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleRequestRejectedException(@NonNull RequestRejectedException ex) {
        return wrapToJson(RequestRejectedException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(ConcurrencyFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String handleConcurrencyFailureException(@NonNull ConcurrencyFailureException ex) {
        return wrapToJson("Conflict of concurrent operations. Try again.");
    }

    @ResponseBody
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String handleNoResourceFoundException(@NonNull NoResourceFoundException ex) {
        return wrapToJson(NoResourceFoundException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    String handleMaxUploadSizeExceededException(@NonNull MaxUploadSizeExceededException ex) {
        return wrapToJson("File is too large.");
    }

    @ResponseBody
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    String handleHttpMediaTypeNotAcceptableException(@NonNull HttpMediaTypeNotAcceptableException ex) {
        return wrapToJson("Media type not acceptable.");
    }

    @ResponseBody
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    String handleHttpMediaTypeNotSupportedException(@NonNull HttpMediaTypeNotSupportedException ex) {
        return wrapToJson("Media type not supported.");
    }

    @ResponseBody
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    String handleHttpRequestMethodNotSupportedException(@NonNull HttpRequestMethodNotSupportedException ex) {
        return wrapToJson(HttpRequestMethodNotSupportedException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String handleAuthenticationException(@NonNull AuthenticationException ex) {
        return wrapToJson(AuthenticationException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String handleNullPointerException(@NonNull NullPointerException ex) {
        log.error("Unexpected null pointer exception: {}", ex.getMessage(), ex);
        return wrapToJson(NullPointerException.class.getSimpleName());
    }

    @ResponseBody
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    String handleException(@NonNull Exception ex) {
        log.error("Unexpected unrecognised internal server exception: {}", ex.getMessage(), ex);
        return wrapToJson("Unexpected unrecognised internal server exception.");
    }

    @ExceptionHandler(IOException.class)
    void handleIOException(@NonNull IOException ex) {
        // do nothing, sending a response can cause another exception
    }

    // endregion

    // region Helper Methods

    String wrapToJson(String message, Map<String, Object> details) {
        String fallback = "{\"message\":\"" + message + "\"}";

        if (details == null) {
            return fallback;
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("message", message, "details", details));
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    String wrapToJson(String message) {
        return wrapToJson(message, null);
    }

    // endregion

}
