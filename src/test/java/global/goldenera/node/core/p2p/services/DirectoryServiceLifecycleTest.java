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
package global.goldenera.node.core.p2p.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.node.NodeTerminationService;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadiness;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Client;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Serializer;
import global.goldenera.node.core.p2p.directory.DirectoryNodeUpgradeRequiredException;
import global.goldenera.node.core.properties.DirectoryProperties;
import global.goldenera.node.core.properties.P2PProperties;
import global.goldenera.node.shared.properties.GeneralProperties;

class DirectoryServiceLifecycleTest {

	@Test
	void constructionDoesNotScheduleOrPingAndExplicitStartIsIdempotent() {
		Fixture fixture = new Fixture();

		fixture.service.pingDirectory();
		verify(fixture.scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
		verify(fixture.chain, never()).getLatestStoredBlockOrThrow();

		assertThat(fixture.service.start(32123)).isTrue();
		assertThat(fixture.service.start(32123)).isFalse();
		assertThat(fixture.service.advertisedP2pPort()).isEqualTo(32123);
		verify(fixture.scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));

		fixture.service.pingDirectory();
		verify(fixture.chain, never()).getLatestStoredBlockOrThrow();
	}

	@Test
	void refusesConfiguredPortZeroBecauseOnlyTheActualBoundPortMayBeAdvertised() {
		Fixture fixture = new Fixture();

		assertThatThrownBy(() -> fixture.service.start(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.service.isStarted()).isFalse();
	}

	@Test
	void typedUpgradeResponseRequestsControlledNodeTermination() {
		Fixture fixture = new Fixture();

		fixture.service.handlePingFailure(
				new DirectoryNodeUpgradeRequiredException("upgrade", "0.1.0", "0.1.1"));

		verify(fixture.termination).terminateForRequiredUpgrade("0.1.1");
	}

	private static final class Fixture {

		private final ChainQuery chain = mock(ChainQuery.class);
		private final ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		private final CoreRuntimeReadiness readiness = mock(CoreRuntimeReadiness.class);
		private final NodeTerminationService termination = mock(NodeTerminationService.class);
		private final DirectoryService service;

		private Fixture() {
			IdentityService identity = mock(IdentityService.class);
			when(identity.getNodeIdentityAddress()).thenReturn(Address.ZERO);
			GeneralProperties general = new GeneralProperties();
			general.setNetwork(Network.MAINNET);
			P2PProperties p2p = new P2PProperties();
			p2p.setHost("127.0.0.1");
			p2p.setPort(0);
			DirectoryProperties directory = new DirectoryProperties();
			directory.setDisable(false);
			service = new DirectoryService(
					mock(DirectoryApiV1Client.class),
					mock(DirectoryApiV1Serializer.class),
					identity,
					general,
					p2p,
					chain,
					directory,
					readiness,
					termination,
					scheduler);
		}
	}
}
