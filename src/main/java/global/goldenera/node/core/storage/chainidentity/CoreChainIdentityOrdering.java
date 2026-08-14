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
package global.goldenera.node.core.storage.chainidentity;

import java.util.Arrays;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * Enforces the core-only lifecycle: settings/path preflight -&gt; writable Rocks
 * open -&gt; local genesis verification -&gt; authoritative Rocks identity bind
 * -&gt; consensus runtime. It intentionally has no Liquibase or DataSource edge.
 */
public final class CoreChainIdentityOrdering implements BeanFactoryPostProcessor, PriorityOrdered {

	public static final String CORE_BOOTSTRAP_BEAN_NAME = "coreBootstrapService";
	public static final String NETWORK_SETTINGS_BEAN_NAME = "networkSettingsProvider";
	private static final String[] GUARDED_CORE_BEANS = {
			CORE_BOOTSTRAP_BEAN_NAME,
			"directoryService",
			"nodeConnectionManager",
			"nettyP2PServer",
			"randomXManager",
			"miningService",
			"mempoolManager",
			"mempoolStore",
			"blockOrphanBufferService",
			"mempoolPropagationService"
	};

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
			throw new ChainStorageGuardException(
					"Bean factory cannot enforce core chain identity bootstrap ordering");
		}
		require(beanFactory, ChainIdentityPathPreflight.BEAN_NAME);
		require(beanFactory, ChainIdentityGenesisVerifier.BEAN_NAME);
		require(beanFactory, ChainIdentityBindingInitializer.BEAN_NAME);
		require(beanFactory, NETWORK_SETTINGS_BEAN_NAME);

		BeanDefinition verifier = registry.getBeanDefinition(ChainIdentityGenesisVerifier.BEAN_NAME);
		addDependency(verifier, "blockchainDB");
		BeanDefinition binder = registry.getBeanDefinition(ChainIdentityBindingInitializer.BEAN_NAME);
		addDependency(binder, ChainIdentityGenesisVerifier.BEAN_NAME);
		for (String guardedBean : GUARDED_CORE_BEANS) {
			if (registry.containsBeanDefinition(guardedBean)) {
				addDependency(registry.getBeanDefinition(guardedBean),
						ChainIdentityBindingInitializer.BEAN_NAME);
				addDependency(registry.getBeanDefinition(guardedBean), NETWORK_SETTINGS_BEAN_NAME);
			}
		}
	}

	private void require(ConfigurableListableBeanFactory beanFactory, String beanName) {
		if (!beanFactory.containsBeanDefinition(beanName) && !beanFactory.containsSingleton(beanName)) {
			throw new ChainStorageGuardException(
					"Required core chain identity lifecycle bean is missing: " + beanName);
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
