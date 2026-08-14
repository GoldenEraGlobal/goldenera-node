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
package global.goldenera.node.explorer.storage.chainidentity;

import java.util.Arrays;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

import global.goldenera.node.core.storage.chainidentity.ChainStorageGuardException;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityBindingInitializer;

/** Core identity -&gt; non-fatal explorer SQL boundary -&gt; guarded workers. */
public final class ExplorerChainIdentityOrdering implements BeanFactoryPostProcessor, PriorityOrdered {

	private static final String[] LAZY_SQL_INFRASTRUCTURE = {
			"liquibase",
			"entityManagerFactory",
			"transactionManager"
	};

	private static final String[] GUARDED_EXPLORER_BEANS = {
			"exIndexerCoordinateService",
			"exIndexerMempoolService",
			"exIndexerQueueService",
			"webhookDispatchService"
	};

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
			throw new ChainStorageGuardException(
					"Bean factory cannot enforce explorer chain identity ordering");
		}
		if (!registry.containsBeanDefinition(ExplorerChainIdentityInitializer.BEAN_NAME)) {
			throw new ChainStorageGuardException("Explorer chain identity initializer bean is missing");
		}
		BeanDefinition initializer = registry.getBeanDefinition(ExplorerChainIdentityInitializer.BEAN_NAME);
		addDependency(initializer, ChainIdentityBindingInitializer.BEAN_NAME);
		for (String beanName : LAZY_SQL_INFRASTRUCTURE) {
			if (registry.containsBeanDefinition(beanName)) {
				registry.getBeanDefinition(beanName).setLazyInit(true);
			}
		}
		for (String explorerBean : GUARDED_EXPLORER_BEANS) {
			if (registry.containsBeanDefinition(explorerBean)) {
				addDependency(registry.getBeanDefinition(explorerBean),
						ExplorerChainIdentityInitializer.BEAN_NAME);
			}
		}
	}

	private void addDependency(BeanDefinition definition, String dependency) {
		String[] existing = definition.getDependsOn();
		if (existing == null) {
			definition.setDependsOn(dependency);
			return;
		}
		if (Arrays.stream(existing).noneMatch(dependency::equals)) {
			String[] expanded = Arrays.copyOf(existing, existing.length + 1);
			expanded[existing.length] = dependency;
			definition.setDependsOn(expanded);
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
