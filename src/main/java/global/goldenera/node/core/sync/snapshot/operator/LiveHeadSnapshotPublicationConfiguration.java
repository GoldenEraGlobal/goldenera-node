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
package global.goldenera.node.core.sync.snapshot.operator;

import java.util.concurrent.locks.ReentrantLock;

import org.rocksdb.RocksDB;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.sync.snapshot.publication.CoreSnapshotPublicationGenerator;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ge.core.sync.snapshot", name = "publish-enabled", havingValue = "true")
public class LiveHeadSnapshotPublicationConfiguration {

	@Bean
	@ConditionalOnMissingBean
	LiveHeadCoreSnapshotCloneService automaticLiveHeadCoreSnapshotCloneService(
			RocksDB blockchainDB,
			RocksDbColumnFamilies families,
			BlockchainDbProperties databaseProperties,
			ObjectMapper objectMapper,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock) {
		return new LiveHeadCoreSnapshotCloneService(
				blockchainDB, families, databaseProperties,
				new BlockchainRocksDbFactory(SnapshotCloneResourceLimits.databaseProperties(databaseProperties)),
				masterChainLock, objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	IsolatedLiveHeadSnapshotPublisher automaticIsolatedLiveHeadSnapshotPublisher() {
		return new IsolatedLiveHeadSnapshotPublisher();
	}

	@Bean
	CoreSnapshotPublicationGenerator liveHeadCoreSnapshotPublicationGenerator(
			LiveHeadCoreSnapshotCloneService cloneService,
			IsolatedLiveHeadSnapshotPublisher publisher,
			SnapshotDistributionProperties distributionProperties) {
		OfflineSnapshotOperatorProperties properties = new OfflineSnapshotOperatorProperties();
		properties.setEnabled(true);
		properties.setCheckpointHeight(-1);
		properties.setPublicOrigin(distributionProperties.getPublishPublicOrigin());
		properties.setIncludeExplorer(false);
		return new LiveHeadCoreSnapshotPublicationGenerator(cloneService, publisher, properties);
	}
}
