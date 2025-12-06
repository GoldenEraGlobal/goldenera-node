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
package global.goldenera.node.core.processing.handlers;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.LinkedHashSet;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxPayloadType;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.Constants;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.TxExecutionContext;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.consensus.state.impl.BipStateImpl;
import global.goldenera.node.shared.consensus.state.impl.BipStateMetadataImpl;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
import global.goldenera.node.shared.enums.state.BipStateMetadataVersion;
import global.goldenera.node.shared.enums.state.BipStateVersion;

@Component
public class BipCreateHandler implements TxHandler {

	@Override
	public TxType getSupportedType() {
		return TxType.BIP_CREATE;
	}

	@Override
	public void execute(TxExecutionContext context) {
		WorldState state = context.getState();
		Tx tx = context.getTx();
		SimpleBlock block = context.getBlock();
		NetworkParamsState params = context.getParams();
		// Authority Check
		checkArgument(state.getAuthority(tx.getSender()).exists(), "Sender is not an authority");

		Hash bipHash = tx.getHash();

		// Existence Check
		checkArgument(!state.getBip(bipHash).exists(), "BIP with this hash already exists");

		long currentAuthorityCount = params.getCurrentAuthorityCount();
		long approvalThresholdBps = Constants.getSettings().bipApprovalThresholdBps();
		checkArgument(currentAuthorityCount > 0, "Cannot create BIP: no authorities are defined");

		long requiredVotes = (currentAuthorityCount * approvalThresholdBps + 9999L) / 10000L;

		TxPayload payload = (TxPayload) tx.getPayload();

		Address derivedTokenAddr = payload.getPayloadType() == TxPayloadType.BIP_TOKEN_CREATE
				? Address.generateTokenAddress(tx.getSender(), tx.getNonce())
				: null;

		BipStateMetadataImpl metadata = BipStateMetadataImpl.builder()
				.version(BipStateMetadataVersion.V1)
				.txVersion(tx.getVersion())
				.txPayload(payload)
				.derivedTokenAddress(derivedTokenAddr)
				.build();

		BipStateImpl newBipState = BipStateImpl.builder()
				.version(BipStateVersion.V1)
				.status(BipStatus.PENDING)
				.isActionExecuted(false)
				.type(BipType.fromTxPayloadType(payload.getPayloadType()))
				.numberOfRequiredVotes(requiredVotes)
				.approvers(new LinkedHashSet<>())
				.disapprovers(new LinkedHashSet<>())
				.expirationTimestamp(block.getTimestamp().plusMillis(Constants.getSettings().bipExpirationPeriodMs()))
				.metadata(metadata)
				.originTxHash(bipHash)
				.updatedByTxHash(bipHash)
				.updatedAtBlockHeight(block.getHeight())
				.updatedAtTimestamp(block.getTimestamp())
				.build();

		state.setBip(bipHash, newBipState);
	}
}