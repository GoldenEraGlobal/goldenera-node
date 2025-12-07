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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Intercepts requests to detect API version from the URL path.
 * 
 * <p>
 * Only sets version context for our API endpoints: /api/{module}/v1/,
 * /api/{module}/v2/, etc.
 * where module can be: admin, explorer, shared, core.
 * Other endpoints like /v3/api-docs (OpenAPI) are NOT affected.
 */
public class ApiVersionInterceptor implements HandlerInterceptor {

    /**
     * Pattern to match our API paths: /api/{module}/v{N}/
     * Modules: admin, explorer, shared, core
     * Captures the version number (1, 2, etc.)
     */
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("/api/(?:admin|explorer|shared|core)/v(\\d+)/");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().toLowerCase();

        Matcher matcher = API_VERSION_PATTERN.matcher(uri);
        if (matcher.find()) {
            String versionNumber = matcher.group(1); // "1", "2", etc.
            ApiVersionContext.setVersion("v" + versionNumber); // "v1", "v2", etc.
        }
        // If no match, version context remains null -> use default converter

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        ApiVersionContext.clear();
    }
}