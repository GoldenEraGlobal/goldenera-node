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
package global.goldenera.node.core.p2p.netty.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.chainidentity.P2PChainCapability;
import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy;
import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy.Mode;
import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy.Validation;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolHashesRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PPeerHeadAdvancedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.messages.NetworkMessage;
import global.goldenera.node.core.p2p.messages.P2PEnvelope;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PTxDto;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesDto;
import global.goldenera.node.core.p2p.messages.validation.P2PValidation;
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import global.goldenera.node.core.p2p.netty.P2PChannelAttributes;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.P2PStatusProvider;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.properties.ThrottlingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;

class P2PInboundHandlerChainIdentityTest {

	private static final Address LOCAL_IDENTITY =
			Address.fromHexString("0x1111111111111111111111111111111111111111");
	private static final Address REMOTE_IDENTITY =
			Address.fromHexString("0x2222222222222222222222222222222222222222");
	private static final Address DRIFTED_IDENTITY =
			Address.fromHexString("0x3333333333333333333333333333333333333333");
	private static final P2PChainCapability CHAIN_CAPABILITY = new P2PChainCapability(
			Network.TESTNET.getCode(),
			"sandbox-test-chain",
			"0x" + "a".repeat(64),
			"b".repeat(64));
	private static final Validation ACCEPTED = new Validation(Mode.EXPLICIT, CHAIN_CAPABILITY);

	@Test
	void validStatusCompletesHandshakeOnceAndRepeatedStatusIsRejected() {
		Fixture fixture = fixture(Runnable::run);
		P2PStatusDto status = status(REMOTE_IDENTITY, BigInteger.ONE);

		fixture.channel.writeInbound(new P2PEnvelope(7, P2PMessageType.STATUS, status));

		assertThat(fixture.channel.isActive()).isTrue();
		verify(fixture.publisher).publishEvent(any(P2PHandshakeCompletedEvent.class));
		assertThat(fixture.peer.getIdentity()).isEqualTo(REMOTE_IDENTITY);

		fixture.channel.writeInbound(new P2PEnvelope(8, P2PMessageType.STATUS, status));

		assertThat(fixture.channel.isActive()).isFalse();
		verify(fixture.publisher, times(1)).publishEvent(any(P2PHandshakeCompletedEvent.class));
	}

	@Test
	void successfulHandshakeCommitsAnImmutableDeduplicatedCapabilitySnapshot() {
		Fixture fixture = fixture(Runnable::run);
		P2PStatusDto status = status(Network.TESTNET, REMOTE_IDENTITY, BigInteger.ONE,
				List.of("chain-identity-v1", "state-sync-v1", "state-sync-v1"));

		fixture.channel.writeInbound(new P2PEnvelope(7, P2PMessageType.STATUS, status));

		assertThat(fixture.channel.isActive()).isTrue();
		assertThat(fixture.peer.getCapabilitiesSnapshot())
				.containsExactlyInAnyOrder("chain-identity-v1", "state-sync-v1");
		assertThat(fixture.peer.supportsCapability("state-sync-v1")).isTrue();
		assertThat(fixture.peer.supportsCapability("sync-v2")).isFalse();
		assertThatThrownBy(() -> fixture.peer.getCapabilities().add("mutated"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void legacyEmptyCapabilitiesRemainSupportedAndProduceEmptySnapshot() {
		Fixture fixture = fixture(Runnable::run);

		fixture.channel.writeInbound(new P2PEnvelope(7, P2PMessageType.STATUS,
				status(Network.TESTNET, REMOTE_IDENTITY, BigInteger.ONE, List.of())));

		assertThat(fixture.channel.isActive()).isTrue();
		assertThat(fixture.peer.getCapabilitiesSnapshot()).isEmpty();
		assertThat(fixture.peer.supportsCapability("state-sync-v1")).isFalse();
	}

	@Test
	void rejectedStatusPublishesNoHandshakeEvent() {
		Fixture fixture = fixture(Runnable::run);
		when(fixture.policy.validate(any())).thenThrow(new GEFailedException("chain mismatch"));

		fixture.channel.writeInbound(new P2PEnvelope(
				7,
				P2PMessageType.STATUS,
				status(REMOTE_IDENTITY, BigInteger.ONE)));

		assertThat(fixture.channel.isActive()).isFalse();
		assertThat(fixture.peer.getIdentity()).isNull();
		assertThat(fixture.peer.getCapabilitiesSnapshot()).isEmpty();
		verify(fixture.publisher, never()).publishEvent(any(P2PHandshakeCompletedEvent.class));
	}

	@Test
	void pongCannotRenegotiateHandshakeCapabilities() {
		Fixture fixture = fixture(Runnable::run);
		fixture.channel.writeInbound(new P2PEnvelope(1, P2PMessageType.STATUS,
				status(Network.TESTNET, REMOTE_IDENTITY, BigInteger.ONE, List.of("state-sync-v1"))));

		fixture.channel.writeInbound(new P2PEnvelope(2, P2PMessageType.PONG,
				status(Network.TESTNET, REMOTE_IDENTITY, BigInteger.TEN, List.of("sync-v2"))));

		assertThat(fixture.channel.isActive()).isTrue();
		assertThat(fixture.peer.getCapabilitiesSnapshot()).containsExactly("state-sync-v1");
		assertThat(fixture.peer.supportsCapability("sync-v2")).isFalse();
	}

	@Test
	void configuredOutboundPeerRejectsAValidStatusFromAnotherIdentity() {
		Fixture fixture = fixture(Runnable::run);
		fixture.channel.attr(P2PChannelAttributes.EXPECTED_REMOTE_IDENTITY).set(REMOTE_IDENTITY);

		fixture.channel.writeInbound(new P2PEnvelope(
				7,
				P2PMessageType.STATUS,
				status(DRIFTED_IDENTITY, BigInteger.ONE)));

		assertThat(fixture.channel.isActive()).isFalse();
		verify(fixture.reputation, never()).recordSuccess(any());
		verify(fixture.publisher, never()).publishEvent(any(P2PHandshakeCompletedEvent.class));
	}

	@ParameterizedTest(name = "pre-handshake disposition for {0}")
	@EnumSource(P2PMessageType.class)
	void everyMessageTypeHasExplicitPreHandshakeDisposition(P2PMessageType messageType) {
		QueueExecutor executor = new QueueExecutor();
		Fixture fixture = fixture(executor);
		NetworkMessage payload = messageType == P2PMessageType.STATUS
				? status(REMOTE_IDENTITY, BigInteger.ONE)
				: null;

		fixture.channel.writeInbound(new P2PEnvelope(1, messageType, payload));

		assertThat(executor.pending()).isZero();
		if (messageType == P2PMessageType.STATUS) {
			assertThat(fixture.channel.isActive()).isTrue();
			verify(fixture.publisher).publishEvent(any(P2PHandshakeCompletedEvent.class));
		} else {
			assertThat(fixture.channel.isActive()).isFalse();
			verify(fixture.publisher, never()).publishEvent(any());
			verify(fixture.policy, never()).validate(any());
		}
	}

	@Test
	void preHandshakeTrafficCannotEnterExecutorAndQueuedTrafficCannotBypassLaterRejection() {
		QueueExecutor executor = new QueueExecutor();
		Fixture preHandshake = fixture(executor);

		preHandshake.channel.writeInbound(new P2PEnvelope(1, P2PMessageType.GET_MEMPOOL_HASHES, null));

		assertThat(preHandshake.channel.isActive()).isFalse();
		assertThat(executor.pending()).isZero();
		verify(preHandshake.publisher, never()).publishEvent(any());

		QueueExecutor queuedExecutor = new QueueExecutor();
		Fixture queued = fixture(queuedExecutor);
		P2PStatusDto status = status(REMOTE_IDENTITY, BigInteger.ONE);
		queued.channel.writeInbound(new P2PEnvelope(2, P2PMessageType.STATUS, status));
		clearInvocations(queued.publisher);
		queued.channel.writeInbound(new P2PEnvelope(3, P2PMessageType.GET_MEMPOOL_HASHES, null));
		assertThat(queuedExecutor.pending()).isEqualTo(1);

		queued.channel.writeInbound(new P2PEnvelope(4, P2PMessageType.STATUS, status));
		queuedExecutor.runAll();

		verify(queued.publisher, never()).publishEvent(any(P2PMempoolHashesRequestedEvent.class));
	}

	@Test
	void queuedPingCannotRunAfterStatusRejection() {
		QueueExecutor executor = new QueueExecutor();
		Fixture fixture = fixture(executor);
		P2PStatusDto status = status(REMOTE_IDENTITY, BigInteger.ONE);
		fixture.channel.writeInbound(new P2PEnvelope(1, P2PMessageType.STATUS, status));
		fixture.channel.writeInbound(new P2PEnvelope(2, P2PMessageType.PING, null));
		assertThat(executor.pending()).isEqualTo(1);

		fixture.channel.writeInbound(new P2PEnvelope(3, P2PMessageType.STATUS, status));
		executor.runAll();

		assertThat(fixture.channel.isActive()).isFalse();
		Object outbound = fixture.channel.readOutbound();
		assertThat(outbound).isNull();
	}

	@Test
	void rejectedUnsignedWrongNetworkIdentityDoesNotMutateReputation() {
		Fixture fixture = fixture(Runnable::run);
		P2PStatusDto wrongNetwork = status(Network.MAINNET, REMOTE_IDENTITY, BigInteger.ONE);

		fixture.channel.writeInbound(new P2PEnvelope(1, P2PMessageType.STATUS, wrongNetwork));

		assertThat(fixture.channel.isActive()).isFalse();
		verify(fixture.reputation, never()).ban(any());
		verify(fixture.reputation, never()).recordFailure(any());
		verify(fixture.reputation, never()).recordSuccess(any());
		verify(fixture.publisher, never()).publishEvent(any(P2PHandshakeCompletedEvent.class));
	}

	@Test
	void pongIdentityDriftCannotRefreshLatencyOrMutatePeerHead() {
		Fixture fixture = fixture(Runnable::run);
		P2PStatusDto status = status(REMOTE_IDENTITY, BigInteger.ONE);
		fixture.channel.writeInbound(new P2PEnvelope(1, P2PMessageType.STATUS, status));
		Instant latencyBefore = fixture.peer.getLastPongReceived();

		fixture.channel.writeInbound(new P2PEnvelope(
				2,
				P2PMessageType.PONG,
				status(DRIFTED_IDENTITY, BigInteger.TEN)));

		assertThat(fixture.channel.isActive()).isFalse();
		assertThat(fixture.peer.getIdentity()).isEqualTo(REMOTE_IDENTITY);
		assertThat(fixture.peer.getTotalDifficulty()).isEqualTo(BigInteger.ONE);
		assertThat(fixture.peer.getLastPongReceived()).isEqualTo(latencyBefore);
		verify(fixture.publisher, never()).publishEvent(any(P2PPeerHeadAdvancedEvent.class));
	}

	@Test
	void higherDifficultyPongPublishesOneHeadAdvanceAndStalePongsPublishNone() {
		Fixture fixture = fixture(Runnable::run);
		fixture.channel.writeInbound(new P2PEnvelope(
				1, P2PMessageType.STATUS, status(REMOTE_IDENTITY, BigInteger.ONE)));
		clearInvocations(fixture.publisher);

		fixture.channel.writeInbound(new P2PEnvelope(
				2, P2PMessageType.PONG, status(REMOTE_IDENTITY, BigInteger.TEN)));
		fixture.channel.writeInbound(new P2PEnvelope(
				3, P2PMessageType.PONG, status(REMOTE_IDENTITY, BigInteger.TEN)));
		fixture.channel.writeInbound(new P2PEnvelope(
				4, P2PMessageType.PONG, status(REMOTE_IDENTITY, BigInteger.ONE)));

		assertThat(fixture.peer.getTotalDifficulty()).isEqualTo(BigInteger.TEN);
		verify(fixture.publisher, times(1)).publishEvent(any(P2PPeerHeadAdvancedEvent.class));
	}

	@Test
	void blockBodiesAreForwardedWithoutEagerTransactionValidation() {
		Fixture fixture = fixture(Runnable::run);
		fixture.channel.writeInbound(new P2PEnvelope(
				1, P2PMessageType.STATUS, status(REMOTE_IDENTITY, BigInteger.ONE)));
		clearInvocations(fixture.publisher, fixture.validation);
		Tx transaction = mock(Tx.class);
		P2PBlockBodiesDto bodies = P2PBlockBodiesDto.builder()
				.bodies(List.of(List.of(P2PTxDto.builder().tx(transaction).build())))
				.build();

		fixture.channel.writeInbound(new P2PEnvelope(41, P2PMessageType.BLOCK_BODIES, bodies));

		verify(fixture.validation, never()).validateTxDto(any());
		verify(fixture.publisher).publishEvent(any(P2PBlockBodiesReceivedEvent.class));
	}

	private Fixture fixture(Executor executor) {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		PeerRegistry peerRegistry = mock(PeerRegistry.class);
		PeerReputationService reputation = mock(PeerReputationService.class);
		GeneralProperties generalProperties = mock(GeneralProperties.class);
		when(generalProperties.getNetwork()).thenReturn(Network.TESTNET);
		IdentityService identityService = mock(IdentityService.class);
		when(identityService.getNodeIdentityAddress()).thenReturn(LOCAL_IDENTITY);
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock storedBlock = mock(StoredBlock.class);
		Block block = mock(Block.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(storedBlock);
		when(storedBlock.getCumulativeDifficulty()).thenReturn(BigInteger.ONE);
		when(storedBlock.getBlock()).thenReturn(block);
		when(block.getHeader()).thenReturn(header(LOCAL_IDENTITY));
		P2PChainIdentityPolicy policy = mock(P2PChainIdentityPolicy.class);
		when(policy.localCapabilities()).thenReturn(List.of("chain-identity-v1"));
		when(policy.validate(any())).thenReturn(ACCEPTED);
		ThrottlingProperties throttling = new ThrottlingProperties();
		throttling.setP2pCapacity(100);
		throttling.setP2pRefillTokens(100);
		P2PValidation validation = mock(P2PValidation.class);
		P2PInboundHandler handler = new P2PInboundHandler(
				publisher,
				peerRegistry,
				reputation,
				generalProperties,
				new P2PStatusProvider(chainQuery, generalProperties, identityService, policy),
				executor,
				validation,
				policy,
				new SimpleMeterRegistry(),
				throttling);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.readOutbound();
		ArgumentCaptor<RemotePeer> peer = ArgumentCaptor.forClass(RemotePeer.class);
		verify(peerRegistry).register(peer.capture());
		return new Fixture(channel, publisher, policy, reputation, validation, peer.getValue());
	}

	private P2PStatusDto status(Address identity, BigInteger difficulty) {
		return status(Network.TESTNET, identity, difficulty);
	}

	private P2PStatusDto status(Network network, Address identity, BigInteger difficulty) {
		return status(network, identity, difficulty, List.of("chain-identity-v1"));
	}

	private P2PStatusDto status(Network network, Address identity, BigInteger difficulty,
			List<String> capabilities) {
		return P2PStatusDto.builder()
				.protocolVersion(1)
				.nodeVersion("peer-v1")
				.network(network)
				.nodeIdentity(identity)
				.capabilities(capabilities)
				.cumulativeDifficulty(difficulty)
				.bestBlockHeader(header(identity))
				.build();
	}

	private BlockHeaderImpl header(Address identity) {
		return BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(1)
				.timestamp(Instant.ofEpochSecond(1_800_000_001L))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(identity)
				.nonce(1)
				.signature(Signature.ZERO)
				.build();
	}

	private record Fixture(
			EmbeddedChannel channel,
			ApplicationEventPublisher publisher,
			P2PChainIdentityPolicy policy,
			PeerReputationService reputation,
			P2PValidation validation,
			RemotePeer peer) {
	}

	private static final class QueueExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		int pending() {
			return tasks.size();
		}

		void runAll() {
			while (!tasks.isEmpty()) {
				tasks.remove().run();
			}
		}
	}
}
