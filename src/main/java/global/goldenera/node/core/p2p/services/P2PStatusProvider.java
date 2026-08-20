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

import org.springframework.stereotype.Component;

import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class P2PStatusProvider {

	private final ChainQuery chainQueryService;
	private final GeneralProperties generalProperties;
	private final IdentityService identityService;
	private final P2PChainIdentityPolicy chainIdentityPolicy;

	public P2PStatusDto currentStatus() {
		StoredBlock latestBlock = chainQueryService.getLatestStoredBlockOrThrow();
		return P2PStatusDto.builder()
				.protocolVersion(Constants.P2P_PROTOCOL_VERSION)
				.nodeVersion(Constants.NODE_VERSION)
				.network(generalProperties.getNetwork())
				.cumulativeDifficulty(latestBlock.getCumulativeDifficulty())
				.bestBlockHeader(latestBlock.getBlock().getHeader())
				.nodeIdentity(identityService.getNodeIdentityAddress())
				.capabilities(chainIdentityPolicy.localCapabilities())
				.build();
	}
}
