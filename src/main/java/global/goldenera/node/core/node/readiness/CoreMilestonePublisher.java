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
package global.goldenera.node.core.node.readiness;

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_MILESTONE_EXECUTOR;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Isolates optional milestone observers from the core bootstrap thread. */
@Component
@Slf4j
public final class CoreMilestonePublisher {

	private final ApplicationEventPublisher eventPublisher;
	private final Executor executor;

	public CoreMilestonePublisher(
			ApplicationEventPublisher eventPublisher,
			@Qualifier(CORE_MILESTONE_EXECUTOR) Executor executor) {
		this.eventPublisher = eventPublisher;
		this.executor = executor;
	}

	public boolean publish(ApplicationEvent event) {
		try {
			executor.execute(() -> publishSafely(event));
			return true;
		} catch (RejectedExecutionException e) {
			log.error("Core milestone observer queue rejected {}", event.getClass().getSimpleName());
			return false;
		}
	}

	private void publishSafely(ApplicationEvent event) {
		try {
			eventPublisher.publishEvent(event);
		} catch (RuntimeException e) {
			log.error("Core milestone observer failed for {}: {}",
					event.getClass().getSimpleName(), e.getMessage(), e);
		}
	}
}
