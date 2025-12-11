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
package global.goldenera.node.shared.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookEventDtoV1;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.services.core.WebhookCoreService.WebhookEventFilter;
import global.goldenera.node.shared.utils.ValidatorUtil.Url;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class WebhookValidator {

    private static final int MAX_LABEL_LENGTH = 32;
    private static final int MIN_LABEL_LENGTH = 3;
    private static final String LABEL_REGEX = "^[a-zA-Z0-9_]+$";

    private static final int MAX_DESCRIPTION_LENGTH = 255;
    private static final String DESCRIPTION_REGEX = "^[a-zA-Z0-9 _.-]+$";

    public static String label(String label) throws GEValidationException {
        if (label == null) {
            throw new GEValidationException("Label cannot be null.");
        }

        label = label.trim();
        if (label.isEmpty()) {
            throw new GEValidationException("Label cannot be empty.");
        }
        boolean isValid = label.matches(LABEL_REGEX);
        if (!isValid) {
            throw new GEValidationException(
                    "Label is not acceptable. Only latin chars, numbers and underscores are allowed.");
        }

        if (label.startsWith("_") || label.endsWith("_")) {
            throw new GEValidationException("Label can not start or end with '_'.");
        }

        if (label.length() > MAX_LABEL_LENGTH) {
            throw new GEValidationException("Label cannot be longer than " + MAX_LABEL_LENGTH + " chars.");
        }

        if (label.length() < MIN_LABEL_LENGTH) {
            throw new GEValidationException("Label cannot be shorter than " + MIN_LABEL_LENGTH + " chars.");
        }

        return label;

    }

    public static String description(String description) throws GEValidationException {
        if (description == null) {
            return null;
        }
        description = description.trim();
        if (description.isEmpty()) {
            return null;
        }
        boolean isValid = description.matches(DESCRIPTION_REGEX);
        if (!isValid) {
            throw new GEValidationException("Description '" + description
                    + "' is not acceptable. Only latin chars, numbers, spaces, dots, underscores and hyphens are allowed.");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new GEValidationException(
                    "Description cannot be longer than " + MAX_DESCRIPTION_LENGTH
                            + " chars.");
        }
        return description;
    }

    public static void validateHeadersOrQuery(Map<String, Object> headersOrQuery)
            throws GEValidationException {
        if (headersOrQuery == null || headersOrQuery.isEmpty()) {
            return;
        }
        for (Object value : headersOrQuery.values()) {
            if (value == null) {
                throw new GEValidationException("Headers or query cannot be null.");
            }
            if (value instanceof String str) {
                if (str.trim().isEmpty()) {
                    throw new GEValidationException(
                            "Headers or query value cannot be empty string.");
                }
                continue;
            }
            if (value instanceof Number || value instanceof Boolean) {
                continue;
            }

            throw new GEValidationException(
                    "Headers or query must be a string, number or boolean.");
        }
    }

    public static UrlData url(String url) throws GEValidationException {
        if (url == null) {
            throw new GEValidationException("Url cannot be null.");
        }
        url = url.trim();
        if (!ValidatorUtil.Url.isSafe(url)) {
            throw new GEValidationException("Url '" + url + "' is not safe.");
        }
        url = Url.url(url);
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new GEValidationException("Url '" + url + "' is not valid.", e);
        }
        StringBuilder baseUrlBuilder = new StringBuilder();
        baseUrlBuilder.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            baseUrlBuilder.append(":").append(uri.getPort());
        }
        if (uri.getPath() != null) {
            baseUrlBuilder.append(uri.getPath());
        }
        String baseUrl = baseUrlBuilder.toString();
        Map<String, String> queryParams = new HashMap<>();
        String query = uri.getQuery();
        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                queryParams.put(key, value);
            }
        }
        return new UrlData(baseUrl, queryParams);
    }

    @AllArgsConstructor
    @Getter
    public static class UrlData {
        String url;
        Map<String, String> queryParams;
    }

    @UtilityClass
    public static class WebhookEvent {
        private static ValidationError validateEventCombination(int index, WebhookEventDtoV1 event) {
            if (event.getType() == null) {
                return new ValidationError(index, "event.type", "Event type cannot be null.");
            }

            switch (event.getType()) {
                case NEW_BLOCK:
                    if (event.getAddressFilter() != null || event.getTokenAddressFilter() != null) {
                        return new ValidationError(index, "event.addressFilter",
                                "Invalid event for NEW_BLOCK: 'addressFilter' and 'tokenAddressFilter' must be null.");
                    }
                    break;

                case ADDRESS_ACTIVITY:
                    if (event.getAddressFilter() == null) {
                        return new ValidationError(index, "event.addressFilter",
                                "Invalid event for ADDRESS_ACTIVITY: 'addressFilter' cannot be null.");
                    }
                    break;
            }
            return null;
        }

        public static List<WebhookEventFilter> validateEvents(List<WebhookEventDtoV1> events) {
            List<ValidationError> validationErrors = new ArrayList<>();
            List<WebhookEventFilter> validFilters = new ArrayList<>(events.size());

            for (int i = 0; i < events.size(); i++) {
                WebhookEventDtoV1 event = events.get(i);
                if (event == null) {
                    validationErrors.add(new ValidationError(i, "event", "Event object at this index is null."));
                    continue;
                }
                boolean hasError = false;
                ValidationError error = validateEventCombination(i, event);
                if (error != null) {
                    validationErrors.add(error);
                    hasError = true;
                }

                if (!hasError) {
                    validFilters.add(new WebhookEventFilter(event.getType(), event.getAddressFilter(),
                            event.getTokenAddressFilter()));
                }
            }
            if (!validationErrors.isEmpty()) {
                throw new GEValidationException("Validation failed for one or more items.",
                        Map.of("errors", validationErrors));
            }
            return validFilters;
        }

        @AllArgsConstructor
        @Getter
        private static class ValidationError {
            int index;
            String field;
            String message;
        }
    }
}
