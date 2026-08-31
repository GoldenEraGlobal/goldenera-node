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
package global.goldenera.node.core.sync.snapshot.publication;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.properties.GeneralProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		prefix = "ge.core.sync.snapshot",
		name = "publish-enabled",
		havingValue = "true")
public class SnapshotPublicationConfiguration {

	@Bean
	SnapshotPublicationStore snapshotPublicationStore(
			SnapshotDistributionProperties properties) throws IOException {
		properties.validate();
		return new SnapshotPublicationStore(properties.getPublishDirectory());
	}

	@Bean
	SnapshotPublicationAnchorPolicy snapshotPublicationAnchorPolicy(
			SnapshotDistributionProperties properties,
			ChainQuery chainQuery,
			WorldStateFactory worldStateFactory) {
		return new CanonicalNetworkParamsSnapshotAnchorPolicy(properties, chainQuery, worldStateFactory);
	}

	@Bean
	SnapshotPublicationCoordinator snapshotPublicationCoordinator(
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			ChainQuery chainQuery,
			ObjectProvider<CoreSnapshotPublicationGenerator> coreGenerator,
			ObjectProvider<ExplorerSnapshotPublicationGenerator> explorerGenerator,
			SnapshotPublicationStore store,
			SnapshotPublicationAnchorPolicy anchorPolicy) {
		return new SnapshotPublicationCoordinator(
				properties, generalProperties, chainQuery, coreGenerator, explorerGenerator, store,
				anchorPolicy, Clock.systemUTC());
	}

	@Bean
	ExecutorService snapshotPublicationExecutor() {
		return new ThreadPoolExecutor(
				1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
					Thread thread = new Thread(runnable, "snapshot-publication");
					thread.setDaemon(true);
					thread.setPriority(Thread.MIN_PRIORITY);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	@Bean
	SnapshotPublicationLifecycle snapshotPublicationLifecycle(
			SnapshotPublicationCoordinator coordinator,
			ExecutorService snapshotPublicationExecutor) {
		return new SnapshotPublicationLifecycle(coordinator, snapshotPublicationExecutor);
	}
}
