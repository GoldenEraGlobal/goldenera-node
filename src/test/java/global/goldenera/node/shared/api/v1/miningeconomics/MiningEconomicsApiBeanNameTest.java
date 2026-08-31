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
package global.goldenera.node.shared.api.v1.miningeconomics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class MiningEconomicsApiBeanNameTest {

	@Test
	void productionCoreAndExplorerControllersHaveDistinctBeansAndRoutes() {
		try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
			context.setServletContext(new MockServletContext());
			context.getEnvironment().setActiveProfiles("prod");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"miningEconomicsApiBeanNameTest",
					Map.of("ge.general.explorer-enable", "true")));
			context.register(MiningEconomicsControllerScan.class);
			context.refresh();

			assertThat(context.containsBeanDefinition("coreMiningEconomicsApiV1")).isTrue();
			assertThat(context.containsBeanDefinition("explorerMiningEconomicsApiV1")).isTrue();
			assertThat(handlerPaths(context)).contains(
					"/api/core/v1/blockchain/worldstate/mining-economics",
					"/api/explorer/v1/mining-economics/snapshot");
		}
	}

	private Set<String> handlerPaths(AnnotationConfigWebApplicationContext context) {
		return context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet().stream()
				.flatMap(mapping -> mapping.getPatternValues().stream())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@ComponentScan(
		basePackages = {
				"global.goldenera.node.core.api.v1.miningeconomics",
				"global.goldenera.node.explorer.api.v1.miningeconomics"
		},
		useDefaultFilters = false,
		lazyInit = true,
		includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class))
	static class MiningEconomicsControllerScan {
	}
}
