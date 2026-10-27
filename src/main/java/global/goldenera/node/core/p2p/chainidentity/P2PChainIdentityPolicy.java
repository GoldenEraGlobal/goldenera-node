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

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;

import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesProvider;
import global.goldenera.node.core.node.capabilities.NodeCapabilitiesSnapshot;
import global.goldenera.node.core.node.capabilities.RuntimeNodeCapabilitiesProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.shared.exceptions.GEFailedException;

@Component
public final class P2PChainIdentityPolicy {

	private final SandboxRuntimeContext runtimeContext;
	private final NodeCapabilitiesProvider capabilitiesProvider;
	private final ChainQuery chainQuery;
	private final P2PChainCapabilityCodec codec = new P2PChainCapabilityCodec();

	@Autowired
	public P2PChainIdentityPolicy(
			SandboxRuntimeContext runtimeContext,
			NodeCapabilitiesProvider capabilitiesProvider,
			ChainQuery chainQuery) {
		this.runtimeContext = runtimeContext;
		this.capabilitiesProvider = capabilitiesProvider;
		this.chainQuery = chainQuery;
	}

	/** Test-friendly constructor for policies which do not need a local head. */
	public P2PChainIdentityPolicy(
			SandboxRuntimeContext runtimeContext,
			NodeCapabilitiesProvider capabilitiesProvider) {
		this(runtimeContext, capabilitiesProvider, null);
	}

	public List<String> localCapabilities() {
		NodeCapabilitiesSnapshot snapshot = currentSnapshot();
		TreeSet<String> capabilities = new TreeSet<>(snapshot.capabilityIds());
		capabilities.add(codec.encode(P2PChainCapability.from(snapshot.chainIdentity())));
		List<String> result = capabilities.stream().toList();
		codec.find(result);
		return result;
	}

	public P2PChainCapability validate(P2PStatusDto status) {
		if (status == null || status.getNetwork() == null) {
			throw rejected("Peer status is missing chain identity prerequisites");
		}
		if (status.getCapabilities() == null) {
			throw rejected("Peer status is missing capabilities");
		}
		validateMiningEconomicsCompatibility(status);
		if (!status.getCapabilities().contains(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY)) {
			throw rejected("Peer omitted the required " + P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY + " capability");
		}
		Optional<P2PChainCapability> claimed;
		try {
			claimed = codec.find(status.getCapabilities());
		} catch (IllegalArgumentException e) {
			throw rejected("Malformed peer chain capability");
		}
		if (claimed.isEmpty()) {
			throw rejected("Peer omitted the required chain identity capability");
		}
		P2PChainCapability expected = P2PChainCapability.from(currentSnapshot().chainIdentity());
		if (status.getNetwork().getCode() != claimed.get().carrierNetworkCode() || !expected.equals(claimed.get())) {
			throw rejected("Peer chain identity does not match the authoritative local identity");
		}
		return claimed.get();
	}

	private void validateMiningEconomicsCompatibility(P2PStatusDto status) {
		long peerHeight = status.getBestBlockHeader() == null ? -1 : status.getBestBlockHeader().getHeight();
		long localHeight = chainQuery == null ? -1 : chainQuery.getLatestStoredBlockOrThrow().getHeight();
		long observedHeight = Math.max(peerHeight, localHeight);
		if (Constants.isForkActive(ForkName.MINING_ECONOMICS, observedHeight)
				&& !status.getCapabilities().contains(RuntimeNodeCapabilitiesProvider.MINING_ECONOMICS_V1)) {
			throw rejected("Peer omitted the required mining-economics-v1 capability after activation");
		}
	}

	private NodeCapabilitiesSnapshot currentSnapshot() {
		NodeCapabilitiesSnapshot snapshot = capabilitiesProvider.snapshot();
		if (snapshot.executionDomain() != runtimeContext.executionDomain()) {
			throw rejected("Node capability execution domain is inconsistent");
		}
		return snapshot;
	}

	private GEFailedException rejected(String message) {
		return new GEFailedException(message);
	}
}
