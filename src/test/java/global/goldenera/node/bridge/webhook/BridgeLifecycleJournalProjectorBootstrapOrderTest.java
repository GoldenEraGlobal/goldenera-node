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
package global.goldenera.node.bridge.webhook;

import static global.goldenera.node.bridge.config.BridgeAsyncConfig.BRIDGE_JOURNAL_EXECUTOR;
import static global.goldenera.node.shared.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalBootstrap;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BridgeLifecycleJournalProjectorBootstrapOrderTest {

	private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean();
	private static final AtomicInteger SCHEDULED_AFTER_BOOTSTRAP = new AtomicInteger();

	@BeforeEach
	void resetOrderMarkers() {
		BOOTSTRAPPED.set(false);
		SCHEDULED_AFTER_BOOTSTRAP.set(0);
	}

	@Test
	void schedulesProjectionOnlyAfterApplicationReadyAndLifecycleJournalBootstrap() {
		new ApplicationContextRunner()
				.withPropertyValues(
						"ge.general.postgresql-enable=true",
						"ge.general.webhook-enable=true")
				.withUserConfiguration(ProjectorConfiguration.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(BridgeLifecycleJournalProjector.class);
					assertThat(BOOTSTRAPPED).isTrue();
					assertThat(SCHEDULED_AFTER_BOOTSTRAP).hasValue(0);

					context.publishEvent(mock(ApplicationReadyEvent.class));
					context.publishEvent(mock(ApplicationReadyEvent.class));

					assertThat(SCHEDULED_AFTER_BOOTSTRAP).hasValue(1);
				});
	}

	@Configuration(proxyBeanMethods = false)
	@Import(BridgeLifecycleJournalProjector.class)
	static class ProjectorConfiguration {

		@Bean(name = LifecycleJournalBootstrap.BEAN_NAME)
		InitializingBean lifecycleJournalBootstrapMarker() {
			return () -> BOOTSTRAPPED.set(true);
		}

		@Bean
		LifecycleJournalQuery lifecycleJournalQuery() {
			return mock(LifecycleJournalQuery.class);
		}

		@Bean
		BridgeLifecycleProjectionCursorStore cursorStore() {
			return mock(BridgeLifecycleProjectionCursorStore.class);
		}

		@Bean
		BridgeLifecycleProjectionService projectionService() {
			return mock(BridgeLifecycleProjectionService.class);
		}

		@Bean(name = CORE_WEBHOOK_SCHEDULER)
		TaskScheduler scheduler() {
			TaskScheduler scheduler = mock(TaskScheduler.class);
			when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
					.thenAnswer(invocation -> {
						if (BOOTSTRAPPED.get()) {
							SCHEDULED_AFTER_BOOTSTRAP.incrementAndGet();
						}
						return null;
					});
			return scheduler;
		}

		@Bean(name = BRIDGE_JOURNAL_EXECUTOR)
		Executor journalExecutor() {
			return Runnable::run;
		}

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}
}
