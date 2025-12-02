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

import java.time.Instant;
import java.util.LinkedHashSet;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.state.serialization.bip.BipStateDecodingStrategy;
import global.goldenera.node.core.state.serialization.bip.BipStateMetadataDecoder;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.BipStateMetadata;
import global.goldenera.node.shared.consensus.state.impl.BipStateImpl;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import global.goldenera.rlp.RLPInput;

public class BipStateV1DecodingStrategy implements BipStateDecodingStrategy {

	@Override
	public BipState decode(RLPInput input) {
		// status
		BipStatus status = BipStatus.fromCode(input.readIntScalar());

		// isActionExecuted
		boolean isActionExecuted = input.readIntScalar() == 1;

		// type
		BipType type = BipType.fromCode(input.readIntScalar());

		// numberOfRequiredVotes
		long numberOfRequiredVotes = input.readLongScalar();

		// approvers
		LinkedHashSet<Address> approvers = new LinkedHashSet<>(input.readList(listInput -> {
			return Address.wrap(listInput.readBytes());
		}));

		// disapprovers
		LinkedHashSet<Address> disapprovers = new LinkedHashSet<>(input.readList(listInput -> {
			return Address.wrap(listInput.readBytes());
		}));

		// expirationTimestamp
		Instant expirationTimestamp = Instant.ofEpochMilli(input.readLongScalar());

		// metadata
		BipStateMetadata metadata = BipStateMetadataDecoder.INSTANCE.decode(input.readOptionalRaw());

		// executedAtTimestamp
		Long executedAtTimestampMillis = input.readOptionalLongScalar();
		Instant executedAtTimestamp = executedAtTimestampMillis != null
				? Instant.ofEpochMilli(executedAtTimestampMillis)
				: null;

		// originTxHash
		Hash originTxHash = Hash.wrap(input.readBytes32());

		// updatedByTxHash
		Hash updatedByTxHash = Hash.wrap(input.readBytes32());

		// updatedAtBlockHeight
		long updatedAtBlockHeight = input.readLongScalar();

		// updatedAtTimestamp
		Long updatedAtTimestampMillis = input.readOptionalLongScalar();
		Instant updatedAtTimestamp = updatedAtTimestampMillis != null
				? Instant.ofEpochMilli(updatedAtTimestampMillis)
				: null;

		return BipStateImpl.builder()
				.version(BipStateVersion.V1)
				.status(status)
				.isActionExecuted(isActionExecuted)
				.type(type)
				.numberOfRequiredVotes(numberOfRequiredVotes)
				.approvers(approvers)
				.disapprovers(disapprovers)
				.expirationTimestamp(expirationTimestamp)
				.metadata(metadata)
				.executedAtTimestamp(executedAtTimestamp)
				.originTxHash(originTxHash)
				.updatedByTxHash(updatedByTxHash)
				.updatedAtBlockHeight(updatedAtBlockHeight)
				.updatedAtTimestamp(updatedAtTimestamp)
				.build();
	}

}
