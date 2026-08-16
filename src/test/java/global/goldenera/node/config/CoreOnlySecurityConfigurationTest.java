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
package global.goldenera.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import global.goldenera.node.shared.filters.ThrottlingFilter;
import global.goldenera.node.shared.properties.SecurityProperties;
import global.goldenera.node.shared.services.ThrottlingService;

class CoreOnlySecurityConfigurationTest {

	@Test
	void unsecuredCoreApiRemainsPublicButKeepsRateLimitFilter() throws Exception {
		try (Fixture fixture = fixture(false)) {
			fixture.mockMvc.perform(get("/api/core/probe").with(request -> {
				request.setRemoteAddr("127.0.0.1");
				return request;
			})).andExpect(status().isOk());

			assertThat(fixture.controllerCalls.get()).isOne();
			verify(fixture.throttlingService).checkGlobalIpLimit(any());
			verify(fixture.throttlingService).checkSpecificLimit(any(), anyString(), anyBoolean());
		}
	}

	@Test
	void securedCoreApiIsDeniedFailClosedWhenSqlApiKeysAreUnavailable() throws Exception {
		try (Fixture fixture = fixture(true)) {
			fixture.mockMvc.perform(get("/api/core/v1/health/live"))
					.andExpect(status().isOk());
			fixture.mockMvc.perform(get("/api/core/probe"))
					.andExpect(status().isForbidden());

			assertThat(fixture.controllerCalls.get()).isOne();
		}
	}

	@Test
	void bridgeApiIsDeniedWhenSqlApiKeysAreUnavailable() throws Exception {
		try (Fixture fixture = fixture(false)) {
			fixture.mockMvc.perform(get("/api/bridge/v1/probe"))
					.andExpect(status().isForbidden());

			assertThat(fixture.controllerCalls.get()).isZero();
		}
	}

	private Fixture fixture(boolean coreApiSecurityEnabled) {
		AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
				"coreOnlySecurityConfigurationTest",
				Map.of(
						"ge.general.explorer-enable", "false",
						"ge.general.postgresql-enable", "false",
						"test.core-api-security-enabled", Boolean.toString(coreApiSecurityEnabled))));
		context.register(CoreOnlySecurityConfiguration.class, WebTestConfiguration.class, TestBeans.class);
		context.refresh();
		ThrottlingService throttlingService = context.getBean(ThrottlingService.class);
		AtomicInteger controllerCalls = context.getBean(AtomicInteger.class);
		FilterChainProxy security = context.getBean(FilterChainProxy.class);
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
		return new Fixture(context, mockMvc, throttlingService, controllerCalls);
	}

	@Configuration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		SecurityProperties securityProperties(Environment environment) {
			SecurityProperties properties = mock(SecurityProperties.class);
			when(properties.isCoreApiEnabled()).thenReturn(environment.getProperty(
					"test.core-api-security-enabled", Boolean.class, false));
			return properties;
		}

		@Bean
		ThrottlingService throttlingService() {
			ThrottlingService service = mock(ThrottlingService.class);
			when(service.checkGlobalIpLimit(any())).thenReturn(true);
			when(service.checkSpecificLimit(any(), anyString(), anyBoolean())).thenReturn(true);
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

		@GetMapping("/api/core/probe")
		String probe() {
			calls.incrementAndGet();
			return "ok";
		}

		@GetMapping("/api/core/v1/health/live")
		String live() {
			calls.incrementAndGet();
			return "UP";
		}

		@GetMapping("/api/bridge/v1/probe")
		String bridge() {
			calls.incrementAndGet();
			return "ok";
		}
	}

	private record Fixture(
			AnnotationConfigWebApplicationContext context,
			MockMvc mockMvc,
			ThrottlingService throttlingService,
			AtomicInteger controllerCalls) implements AutoCloseable {

		@Override
		public void close() {
			context.close();
		}
	}
}
