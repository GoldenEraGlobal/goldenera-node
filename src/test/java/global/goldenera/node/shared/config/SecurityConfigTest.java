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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.filters.ThrottlingFilter;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.ThrottlingService;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;

class SecurityConfigTest {

	@Test
	void bridgeApiAlwaysRequiresApiKey() throws Exception {
		try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
			context.setServletContext(new MockServletContext());
			context.register(SecurityConfig.class, WebTestConfiguration.class, TestBeans.class);
			context.refresh();
			FilterChainProxy security = context.getBean(FilterChainProxy.class);
			MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();

			mockMvc.perform(get("/api/bridge/v1/probe"))
					.andExpect(status().isForbidden());
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		GeneralProperties generalProperties() {
			return new GeneralProperties();
		}

		@Bean
		ApiKeyCoreService apiKeyCoreService() {
			return mock(ApiKeyCoreService.class);
		}

		@Bean
		HmacComponent hmacComponent() {
			return mock(HmacComponent.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		ThrottlingService throttlingService() {
			ThrottlingService service = mock(ThrottlingService.class);
			when(service.checkGlobalIpLimit(any())).thenReturn(true);
			when(service.checkSpecificLimit(any(), any(), anyBoolean())).thenReturn(true);
			return service;
		}

		@Bean
		ThrottlingFilter throttlingFilter(ThrottlingService throttlingService) {
			return new ThrottlingFilter(throttlingService);
		}

		@Bean
		AtomicInteger controllerCalls() {
			return new AtomicInteger();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class WebTestConfiguration {

		@Bean
		ProbeController probeController(AtomicInteger calls) {
			return new ProbeController(calls);
		}
	}

	@RestController
	static class ProbeController {

		private final AtomicInteger calls;

		ProbeController(AtomicInteger calls) {
			this.calls = calls;
		}

		@GetMapping("/api/bridge/v1/probe")
		String probe() {
			calls.incrementAndGet();
			return "ok";
		}
	}
}
