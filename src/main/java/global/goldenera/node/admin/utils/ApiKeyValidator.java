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
package global.goldenera.node.admin.utils;

import java.time.Instant;
import java.util.Set;

import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ApiKeyValidator {

    private static final int MAX_LABEL_LENGTH = 32;
    private static final int MIN_LABEL_LENGTH = 3;
    private static final String LABEL_REGEX = "^[a-zA-Z0-9_]+$";

    private static final int MAX_DESCRIPTION_LENGTH = 255;
    private static final String DESCRIPTION_REGEX = "^[a-zA-Z0-9 _.-]+$";

    public static Set<ApiKeyPermission> permissions(Set<ApiKeyPermission> permissions)
            throws GEValidationException {
        if (permissions == null) {
            throw new GEValidationException("Permissions cannot be null.");
        }

        if (permissions.isEmpty()) {
            throw new GEValidationException("Permissions cannot be empty.");
        }

        return permissions;
    }

    public static Instant expiresAt(Instant expiresAt) throws GEValidationException {
        if (expiresAt == null) {
            return null;
        }
        if (expiresAt.isBefore(Instant.now())) {
            throw new GEValidationException("Expires at must be in the future.");
        }
        return expiresAt;
    }

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
            throw new GEValidationException("Label cannot be longer than 24 chars.");
        }

        if (label.length() < MIN_LABEL_LENGTH) {
            throw new GEValidationException("Label cannot be shorter than 3 chars.");
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

    public static Long maxWebhooks(
            Long maxWebhooks) {
        if (maxWebhooks == null || maxWebhooks < 0) {
            return null;
        }
        return maxWebhooks;
    }

}
