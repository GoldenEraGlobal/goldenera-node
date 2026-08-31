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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class ApplicationPropertiesTuningTest {

	@Test
	void keepsBootServletDefaultsAndAuthenticationCacheOverrides() throws IOException {
		Properties properties = new Properties();
		try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
			assertThat(input).isNotNull();
			properties.load(input);
		}

		assertThat(properties).doesNotContainKeys(
				"server.tomcat.threads.max", "server.tomcat.threads.min-spare");
		assertThat(properties.getProperty("ge.api-key-auth-cache.enabled"))
				.isEqualTo("${API_KEY_AUTH_CACHE_ENABLED:true}");
		assertThat(properties.getProperty("ge.api-key-auth-cache.ttl"))
				.isEqualTo("${API_KEY_AUTH_CACHE_TTL:5s}");
	}
}
