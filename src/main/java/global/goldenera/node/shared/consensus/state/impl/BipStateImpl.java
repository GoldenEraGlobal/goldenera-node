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
package global.goldenera.node.shared.consensus.state.impl;

import java.time.Instant;
import java.util.LinkedHashSet;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.BipStateMetadata;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class BipStateImpl implements BipState {

	public static final BipState ZERO = BipStateImpl.builder()
			.version(BipStateVersion.getLatest())
			.isActionExecuted(false)
			.numberOfRequiredVotes(0)
			.approvers(new LinkedHashSet<>())
			.disapprovers(new LinkedHashSet<>())
			.originTxHash(Hash.ZERO)
			.updatedByTxHash(Hash.ZERO)
			.updatedAtBlockHeight(Long.MIN_VALUE)
			.updatedAtTimestamp(Instant.EPOCH)
			.expirationTimestamp(Instant.EPOCH)
			.metadata(null)
			.executedAtTimestamp(null)
			.build();

	BipStateVersion version;
	BipStatus status;
	boolean isActionExecuted;
	BipType type;
	long numberOfRequiredVotes;
	LinkedHashSet<Address> approvers;
	LinkedHashSet<Address> disapprovers;
	Instant expirationTimestamp;
	BipStateMetadata metadata;
	Instant executedAtTimestamp;

	// IMMUTABLE: The creation transaction of this BIP.
	Hash originTxHash;

	// MUTABLE: The last vote transaction that modified this BIP's state.
	Hash updatedByTxHash;

	long updatedAtBlockHeight;
	Instant updatedAtTimestamp;

	/**
	 * Registers a vote, updates counters and last modification hash.
	 */
	public BipStateImpl registerVote(Address voter, BipVoteType voteType, Hash voteTxHash, long blockHeight,
			Instant time) {
		// Create mutable copies of current state
		LinkedHashSet<Address> newApprovers = new LinkedHashSet<>(this.approvers);
		LinkedHashSet<Address> newDisapprovers = new LinkedHashSet<>(this.disapprovers);

		// Remove voter from both lists
		newApprovers.remove(voter);
		newDisapprovers.remove(voter);

		// Add to the correct list
		if (voteType == BipVoteType.APPROVAL) {
			newApprovers.add(voter);
		} else {
			newDisapprovers.add(voter);
		}

		return this.toBuilder()
				.approvers(newApprovers)
				.disapprovers(newDisapprovers)
				.updatedByTxHash(voteTxHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	public BipStateImpl updateStatus(BipStatus newStatus, long blockHeight, Instant time) {
		return this.toBuilder()
				.status(newStatus)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	public BipStateImpl markAsExecuted(long blockHeight, Instant time) {
		return this.toBuilder()
				.isActionExecuted(true)
				.executedAtTimestamp(time)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}
}