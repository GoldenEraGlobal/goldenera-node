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
package global.goldenera.node.core.state.serialization.bip.impl;

import global.goldenera.node.core.state.serialization.bip.BipStateEncodingStrategy;
import global.goldenera.node.core.state.serialization.bip.BipStateMetadataEncoder;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.BipStateMetadata;
import global.goldenera.rlp.RLPOutput;

public class BipStateV1EncodingStrategy implements BipStateEncodingStrategy {

	@Override
	public void encode(RLPOutput out, BipState state) {
		// status
		out.writeIntScalar(state.getStatus().getCode());

		// isActionExecuted
		out.writeIntScalar(state.isActionExecuted() ? 1 : 0);

		// type
		out.writeIntScalar(state.getType().getCode());

		// numberOfRequiredVotes
		out.writeLongScalar(state.getNumberOfRequiredVotes());

		// approvers
		out.writeList(state.getApprovers(), (address, out2) -> out2.writeBytes(address));

		// disapprovers
		out.writeList(state.getDisapprovers(), (address, out2) -> out2.writeBytes(address));

		// expirationTimestamp
		out.writeLongScalar(state.getExpirationTimestamp().toEpochMilli());

		// metadata
		BipStateMetadata metadata = state.getMetadata();
		out.writeOptionalRaw(BipStateMetadataEncoder.INSTANCE.encode(metadata));

		// executedAtTimestamp
		out.writeOptionalLongScalar(
				state.getExecutedAtTimestamp() != null ? state.getExecutedAtTimestamp().toEpochMilli() : null);

		// originTxHash
		out.writeBytes32(state.getOriginTxHash());

		// updatedByTxHash
		out.writeBytes32(state.getUpdatedByTxHash());

		// updatedAtBlockHeight
		out.writeLongScalar(state.getUpdatedAtBlockHeight());

		// updatedAtTimestamp
		out.writeOptionalLongScalar(
				state.getUpdatedAtTimestamp() != null ? state.getUpdatedAtTimestamp().toEpochMilli() : null);
	}
}
