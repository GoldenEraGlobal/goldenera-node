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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

class CoreChainIdentityOrderingTest {

	@Test
	void coreOrderingHasNoLiquibaseEdge() {
		List<String> events = new ArrayList<>();
		ChainIdentityBootstrapCoordinator coordinator = mock(ChainIdentityBootstrapCoordinator.class);
		RecordingGenesisVerifier verifier = new RecordingGenesisVerifier(events);
		doAnswer(invocation -> {
			events.add("preflight");
			return null;
		}).when(coordinator).preflightBeforeOpeningStorage();
		doAnswer(invocation -> {
			events.add("bind-rocks");
			return null;
		}).when(coordinator).bindAfterGenesisVerification(verifier);

		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerBeanDefinition(ChainIdentityPathPreflight.BEAN_NAME,
				new RootBeanDefinition(ChainIdentityPathPreflight.class,
						() -> new ChainIdentityPathPreflight(coordinator)));
		RootBeanDefinition database = new RootBeanDefinition(RecordingBean.class,
				() -> new RecordingBean(events, "rocks-open"));
		database.setDependsOn(ChainIdentityPathPreflight.BEAN_NAME);
		beans.registerBeanDefinition("blockchainDB", database);
		beans.registerBeanDefinition(ChainIdentityGenesisVerifier.BEAN_NAME,
				new RootBeanDefinition(RecordingGenesisVerifier.class, () -> verifier));
		beans.registerBeanDefinition(ChainIdentityBindingInitializer.BEAN_NAME,
				new RootBeanDefinition(ChainIdentityBindingInitializer.class,
						() -> new ChainIdentityBindingInitializer(coordinator, verifier)));
		beans.registerBeanDefinition(CoreChainIdentityOrdering.NETWORK_SETTINGS_BEAN_NAME,
				new RootBeanDefinition(Object.class));
		beans.registerBeanDefinition(CoreChainIdentityOrdering.CORE_BOOTSTRAP_BEAN_NAME,
				new RootBeanDefinition(RecordingBean.class,
						() -> new RecordingBean(events, "core")));

		new CoreChainIdentityOrdering().postProcessBeanFactory(beans);
		beans.preInstantiateSingletons();

		assertThat(events).containsExactly(
				"preflight", "rocks-open", "verify-genesis", "bind-rocks", "core");
	}

	@Test
	void failsClosedWhenCoreLifecycleBoundaryIsMissing() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

		assertThatThrownBy(() -> new CoreChainIdentityOrdering().postProcessBeanFactory(beans))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("lifecycle bean is missing");
	}

	private record RecordingGenesisVerifier(List<String> events)
			implements InitializingBean, ChainIdentityBindingVerifier {

		@Override
		public void afterPropertiesSet() {
			events.add("verify-genesis");
		}

		@Override
		public void verifyBeforeBinding(ChainIdentityExpectation expectation) {
		}
	}

	private record RecordingBean(List<String> events, String event) implements InitializingBean {

		@Override
		public void afterPropertiesSet() {
			events.add(event);
		}
	}
}
