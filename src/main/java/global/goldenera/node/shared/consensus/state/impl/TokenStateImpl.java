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

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.shared.consensus.state.TokenState;
import global.goldenera.node.shared.enums.state.TokenStateVersion;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class TokenStateImpl implements TokenState {

	public static final TokenState ZERO = TokenStateImpl.builder()
			.version(TokenStateVersion.getLatest())
			.numberOfDecimals(0)
			.originTxHash(Hash.ZERO)
			.updatedByTxHash(Hash.ZERO)
			.totalSupply(Wei.ZERO)
			.updatedAtBlockHeight(Long.MIN_VALUE)
			.updatedAtTimestamp(Instant.EPOCH)
			.userBurnable(false)
			.build();

	TokenStateVersion version;
	String name;
	String smallestUnitName;
	int numberOfDecimals;
	String websiteUrl;
	String logoUrl;
	BigInteger maxSupply;
	boolean userBurnable;

	// IMMUTABLE: Transaction hash that created this token.
	Hash originTxHash;

	// MUTABLE: Transaction hash that performed the last modification (mint, burn,
	// update).
	Hash updatedByTxHash;

	Wei totalSupply;
	long updatedAtBlockHeight;
	Instant updatedAtTimestamp;

	/**
	 * Mints new tokens (increases total supply).
	 */
	public TokenStateImpl mint(Wei amount, Hash txHash, long blockHeight, Instant time) {
		// NOTE: MaxSupply check should be done by the caller (Handler) or added here if
		// strict enforcement is needed in state.
		return this.toBuilder()
				.totalSupply(this.totalSupply.add(amount))
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	/**
	 * Burns tokens (decreases total supply).
	 */
	public TokenStateImpl burn(Wei amount, Hash txHash, long blockHeight, Instant time) {
		return this.toBuilder()
				.totalSupply(this.totalSupply.subtractExact(amount))
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	/**
	 * Updates editable token details.
	 */
	public TokenStateImpl updateDetails(TxBipTokenUpdatePayload payload, Hash txHash, long blockHeight, Instant time) {
		return this.toBuilder()
				.name(payload.getName() != null ? payload.getName() : this.name)
				.smallestUnitName(
						payload.getSmallestUnitName() != null ? payload.getSmallestUnitName() : this.smallestUnitName)
				.websiteUrl(payload.getWebsiteUrl() != null ? payload.getWebsiteUrl() : this.websiteUrl)
				.logoUrl(payload.getLogoUrl() != null ? payload.getLogoUrl() : this.logoUrl)
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}
}