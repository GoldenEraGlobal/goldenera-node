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
package global.goldenera.node.shared.consensus.state;

import java.time.Instant;
import java.util.LinkedHashSet;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
import global.goldenera.node.shared.enums.state.BipStateVersion;

public interface BipState {

	BipStateVersion getVersion();

	BipStatus getStatus();

	boolean isActionExecuted();

	BipType getType();

	long getNumberOfRequiredVotes();

	LinkedHashSet<Address> getApprovers();

	LinkedHashSet<Address> getDisapprovers();

	Instant getExpirationTimestamp();

	BipStateMetadata getMetadata();

	Instant getExecutedAtTimestamp();

	Hash getOriginTxHash();

	Hash getUpdatedByTxHash();

	long getUpdatedAtBlockHeight();

	Instant getUpdatedAtTimestamp();

	/**
	 * Checks if the BIP exists.
	 */
	default boolean exists() {
		return getUpdatedAtBlockHeight() != Long.MIN_VALUE;
	}

	default long getApprovalCount() {
		return getApprovers().size();
	}

	default long getDisapprovalCount() {
		return getDisapprovers().size();
	}

	default LinkedHashSet<Address> getAllVoters() {
		LinkedHashSet<Address> all = new LinkedHashSet<>(getApprovers());
		all.addAll(getDisapprovers());
		return all;
	}
}
