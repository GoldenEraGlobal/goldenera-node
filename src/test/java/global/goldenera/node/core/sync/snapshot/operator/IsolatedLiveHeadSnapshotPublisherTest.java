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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import global.goldenera.node.core.properties.BlockchainDbProperties;

class IsolatedLiveHeadSnapshotPublisherTest {

	@Test
	void exportApplicationIsNonWebAndLazy() {
		LiveHeadCloneExportCapability capability = mock(LiveHeadCloneExportCapability.class);
		SpringApplicationBuilder application =
				new IsolatedLiveHeadSnapshotPublisher().exportApplication(capability);
		SpringApplication configured = application.application();

		assertThat(configured.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
		assertThat(configured.getInitializers()).isNotEmpty();
		assertThat(configured)
				.extracting(
						"properties.lazyInitialization",
						"properties.bannerMode",
						"properties.logStartupInfo")
				.containsExactly(true, Banner.Mode.OFF, false);
	}

	@Test
	void cloneDatabaseLimitsStayBoundedWithoutMutatingProductionConfiguration() {
		BlockchainDbProperties production = new BlockchainDbProperties();
		production.setPath("production");
		production.setRocksdbBlockCacheMb(2_048);
		production.setRocksdbWriteBufferMb(128);
		production.setRocksdbMaxWriteBuffers(4);
		production.setRocksdbBlobFileSizeMb(512);

		BlockchainDbProperties clone = SnapshotCloneResourceLimits.databaseProperties(production);

		assertThat(clone.getPath()).isEqualTo("production");
		assertThat(clone.getRocksdbBlockCacheMb()).isEqualTo(64);
		assertThat(clone.getRocksdbWriteBufferMb()).isEqualTo(8);
		assertThat(clone.getRocksdbMaxWriteBuffers()).isEqualTo(2);
		assertThat(clone.getRocksdbBlobFileSizeMb()).isEqualTo(512);
		assertThat(production.getRocksdbBlockCacheMb()).isEqualTo(2_048);
		assertThat(production.getRocksdbWriteBufferMb()).isEqualTo(128);
		assertThat(production.getRocksdbMaxWriteBuffers()).isEqualTo(4);
	}
}
