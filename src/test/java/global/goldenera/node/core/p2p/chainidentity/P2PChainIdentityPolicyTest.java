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
package global.goldenera.node.core.p2p.chainidentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.ProductionNetworkSettingsExtension;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesProvider;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesSnapshot;
import global.goldenera.node.core.node.capabilities.ProofOfWorkRuntimeMode;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class P2PChainIdentityPolicyTest {

	private static final Address PEER = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

	@TempDir
	Path temporaryDirectory;

	@Test
	void sandboxPeersRequireAndAcceptTheExactAuthoritativeIdentity() throws Exception {
		Fixture fixture = sandboxFixture();
		List<String> exact = withBlockSync(fixture.policy.localCapabilities());

		assertThat(fixture.policy.validate(status(PEER, exact)))
				.isEqualTo(P2PChainCapability.from(fixture.identity));

		P2PChainCapability mismatch = new P2PChainCapability(
				Network.TESTNET.getCode(),
				fixture.identity.chainId() + "-other",
				fixture.identity.genesisHash(),
				fixture.identity.manifestFingerprint());
		assertThatThrownBy(() -> fixture.policy.validate(status(PEER, withBlockSync(List.of(encode(mismatch))))))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("does not match");
	}

	@Test
	void peerWithoutAWellFormedChainIdentityCapabilityIsRejected() throws Exception {
		Fixture fixture = sandboxFixture();

		assertThatThrownBy(() -> fixture.policy.validate(status(PEER, withBlockSync(List.of()))))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("chain identity capability");
		assertThatThrownBy(() -> fixture.policy.validate(status(PEER, withBlockSync(List.of("ge.chain.v1:")))))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Malformed");
	}

	@Test
	void peerWithoutBlockSyncV2IsRejected() {
		StoredChainIdentity identity = productionIdentity(Network.TESTNET);
		P2PChainIdentityPolicy policy = policy(
				new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, Network.TESTNET, Optional.empty()),
				identity);

		assertThatThrownBy(() -> policy.validate(status(PEER, List.of(encode(P2PChainCapability.from(identity))))))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY);
	}

	@Test
	void productionRejectsExplicitIdentityMismatch() {
		StoredChainIdentity identity = productionIdentity(Network.TESTNET);
		P2PChainIdentityPolicy policy = policy(
				new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, Network.TESTNET, Optional.empty()),
				identity);
		P2PChainCapability mismatch = new P2PChainCapability(
				Network.TESTNET.getCode(),
				identity.chainId(),
				"0x" + "2".repeat(64),
				null);

		assertThat(policy.validate(status(PEER, capabilitiesFor(identity))))
				.isEqualTo(P2PChainCapability.from(identity));
		assertThatThrownBy(() -> policy.validate(status(PEER, withBlockSync(List.of(encode(mismatch))))))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("does not match");
	}

	@ParameterizedTest
	@CsvSource({ "MAINNET,753996", "TESTNET,716824" })
	void productionRequiresMiningEconomicsCapabilityExactlyAtEachNetworkActivation(
			Network network, long activationHeight) {
		initializeProductionNetwork(network);
		StoredChainIdentity identity = productionIdentity(network);
		P2PChainIdentityPolicy policy = policy(
				new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, network, Optional.empty()),
				identity);

		assertThat(policy.validate(status(network, PEER, capabilitiesFor(identity), activationHeight - 1)))
				.isEqualTo(P2PChainCapability.from(identity));
		assertThatThrownBy(() -> policy.validate(status(network, PEER, capabilitiesFor(identity), activationHeight)))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("mining-economics-v1");
		assertThat(policy.validate(status(
				network, PEER, capabilitiesFor(identity, "mining-economics-v1"), activationHeight)))
				.isEqualTo(P2PChainCapability.from(identity));
	}

	@Test
	void activatedLocalNodeRejectsPeerWithoutMiningEconomicsStillReportingPreForkHead() {
		StoredChainIdentity identity = productionIdentity(Network.TESTNET);
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.TESTNET, Optional.empty());
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock localHead = mock(StoredBlock.class);
		when(localHead.getHeight()).thenReturn(753_996L);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(localHead);

		assertThatThrownBy(() -> policy(runtime, identity, chainQuery)
				.validate(status(PEER, capabilitiesFor(identity), 753_995)))
				.hasMessageContaining("mining-economics-v1");
	}

	private StoredChainIdentity productionIdentity(Network network) {
		return new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				network.getCode(),
				network.name().toLowerCase() + "-production",
				"0x" + "1".repeat(64),
				null);
	}

	private List<String> capabilitiesFor(StoredChainIdentity identity, String... extra) {
		List<String> capabilities = new ArrayList<>(List.of(extra));
		capabilities.add(encode(P2PChainCapability.from(identity)));
		return withBlockSync(capabilities);
	}

	private List<String> withBlockSync(List<String> capabilities) {
		List<String> result = new ArrayList<>(capabilities);
		result.add(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY);
		return result;
	}

	private String encode(P2PChainCapability capability) {
		return new P2PChainCapabilityCodec().encode(capability);
	}

	private Fixture sandboxFixture() throws Exception {
		SandboxManifestContext context = manifest();
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				Network.TESTNET.getCode(),
				context.manifest().chainId(),
				context.manifest().genesis().expectedGenesisHash(),
				context.fingerprint());
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX,
				Network.TESTNET,
				Optional.of(context));
		return new Fixture(policy(runtime, identity), identity);
	}

	private P2PChainIdentityPolicy policy(SandboxRuntimeContext runtime, StoredChainIdentity identity) {
		return policy(runtime, identity, null);
	}

	private P2PChainIdentityPolicy policy(
			SandboxRuntimeContext runtime,
			StoredChainIdentity identity,
			ChainQuery chainQuery) {
		NodeCapabilitiesProvider provider = mock(NodeCapabilitiesProvider.class);
		when(provider.snapshot()).thenReturn(new NodeCapabilitiesSnapshot(
				1,
				runtime.executionDomain(),
				identity,
				ProofOfWorkRuntimeMode.DETERMINISTIC_SHA256_V1,
				List.of("chain-identity-v1")));
		return chainQuery == null
				? new P2PChainIdentityPolicy(runtime, provider)
				: new P2PChainIdentityPolicy(runtime, provider, chainQuery);
	}

	private SandboxManifestContext manifest() throws Exception {
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			Path path = temporaryDirectory.resolve("manifest-" + System.nanoTime() + ".json");
			Files.writeString(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
			return new SandboxManifestLoader().load(path);
		}
	}

	private P2PStatusDto status(Address identity, List<String> capabilities) {
		return status(identity, capabilities, 0);
	}

	private P2PStatusDto status(Address identity, List<String> capabilities, long height) {
		return status(Network.TESTNET, identity, capabilities, height);
	}

	private P2PStatusDto status(Network network, Address identity, List<String> capabilities, long height) {
		return P2PStatusDto.builder()
				.protocolVersion(1)
				.nodeVersion("test")
				.network(network)
				.nodeIdentity(identity)
				.capabilities(capabilities)
				.cumulativeDifficulty(BigInteger.ONE)
				.bestBlockHeader(BlockHeaderImpl.builder()
						.version(BlockVersion.V1)
						.height(height)
						.timestamp(Instant.EPOCH)
						.previousHash(Hash.ZERO)
						.txRootHash(Hash.ZERO)
						.stateRootHash(Hash.ZERO)
						.difficulty(BigInteger.ONE)
						.coinbase(identity)
						.nonce(0)
						.signature(Signature.ZERO)
						.build())
				.build();
	}

	private void initializeProductionNetwork(Network network) {
		ProductionNetworkSettingsExtension.install(network);
	}

	private record Fixture(P2PChainIdentityPolicy policy, StoredChainIdentity identity) {
	}
}
