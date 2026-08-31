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
package global.goldenera.node.core.api.v1.info;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockHeaderDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockHeaderMapper;
import global.goldenera.node.core.api.v1.info.dtos.NodeInfoDtoV1;
import global.goldenera.node.core.api.v1.info.dtos.OpenApiGroupDtoV1;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesProvider;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesSnapshot;
import global.goldenera.node.core.node.capabilities.ProofOfWorkRuntimeMode;
import global.goldenera.node.core.node.metadata.NodeBuildMetadata;
import global.goldenera.node.core.node.metadata.NodeBuildMetadataProvider;
import global.goldenera.node.core.node.metadata.NodeOpenApiGroup;
import global.goldenera.node.core.node.metadata.NodeOpenApiGroupsProvider;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class NodeInfoApiV1Test {

	@Test
	void exposesOneAuthoritativeCapabilitySnapshotAndAnchorsItToTheReportedHead() {
		ChainQuery chain = mock(ChainQuery.class);
		IdentityService identity = mock(IdentityService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		BlockHeaderMapper mapper = mock(BlockHeaderMapper.class);
		NodeCapabilitiesProvider capabilities = mock(NodeCapabilitiesProvider.class);
		NodeBuildMetadataProvider build = mock(NodeBuildMetadataProvider.class);
		NodeOpenApiGroupsProvider groups = mock(NodeOpenApiGroupsProvider.class);
		BlockHeader header = mock(BlockHeader.class);
		Block block = mock(Block.class);
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getBlock()).thenReturn(block);
		when(stored.getCumulativeDifficulty()).thenReturn(BigInteger.TEN);
		when(block.getHeader()).thenReturn(header);
		when(header.getHeight()).thenReturn(42L);
		when(header.getHash()).thenReturn(Hash.ZERO);
		when(header.getStateRootHash()).thenReturn(Hash.ZERO);
		when(chain.getLatestStoredBlock()).thenReturn(Optional.of(stored));
		when(identity.getNodeIdentityAddress()).thenReturn(Address.ZERO);
		when(peers.getAll()).thenReturn(List.of());
		when(mapper.map(header)).thenReturn(mock(BlockHeaderDtoV1.class));
		StoredChainIdentity chainIdentity = new StoredChainIdentity(
				1, 1, "sandbox-test", "0x" + "01".repeat(32), "02".repeat(32));
		when(capabilities.snapshot()).thenReturn(new NodeCapabilitiesSnapshot(
				1,
				ExecutionDomain.SANDBOX,
				chainIdentity,
				ProofOfWorkRuntimeMode.DETERMINISTIC_SHA256_V1,
				List.of("chain-identity-v1", "pow-deterministic-sha256-v1")));
		when(build.metadata()).thenReturn(new NodeBuildMetadata(
				"1.2.3", "abc", "4.5.6", "def", "randomx", "21", "vendor", "vm", "os", "arch"));
		when(groups.groups()).thenReturn(List.of(new NodeOpenApiGroup(
				"CORE API", "/v3/api-docs/CORE%20API")));
		NodeInfoApiV1 api = new NodeInfoApiV1(
				chain, identity, peers, mapper, capabilities, build, groups);

		NodeInfoDtoV1 response = api.getNodeInfo().getBody();

		assertThat(response.getExecutionDomain()).isEqualTo(ExecutionDomain.SANDBOX);
		assertThat(response.getChainIdentity().genesisHash()).isEqualTo(chainIdentity.genesisHash());
		assertThat(response.getChainIdentity().manifestFingerprint())
				.isEqualTo(chainIdentity.manifestFingerprint());
		assertThat(response.getProofOfWorkMode())
				.isEqualTo(ProofOfWorkRuntimeMode.DETERMINISTIC_SHA256_V1);
		assertThat(response.getAnchor().height()).isEqualTo(42L);
		assertThat(response.getAnchor().blockHash()).isEqualTo(Hash.ZERO.toHexString());
		assertThat(response.getBuildMetadata().gitCommit()).isEqualTo("abc");
		assertThat(response.getBuildMetadata().randomXSourceCommit()).isEqualTo("randomx");
		assertThat(response.getOpenApiGroups()).containsExactly(
				new OpenApiGroupDtoV1(
						"CORE API", "/v3/api-docs/CORE%20API"));
	}
}
