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
package global.goldenera.node.core.state.serialization.token.impl;

import global.goldenera.node.core.state.serialization.token.TokenStateEncodingStrategy;
import global.goldenera.node.shared.consensus.state.TokenState;
import global.goldenera.rlp.RLPOutput;

public class TokenStateV1EncodingStrategy implements TokenStateEncodingStrategy {

	@Override
	public void encode(RLPOutput out, TokenState state) {
		out.writeString(state.getName());
		out.writeString(state.getSmallestUnitName());
		out.writeIntScalar(state.getNumberOfDecimals());
		out.writeOptionalString(state.getWebsiteUrl());
		out.writeOptionalString(state.getLogoUrl());
		out.writeOptionalBigIntegerScalar(state.getMaxSupply());
		out.writeIntScalar(state.isUserBurnable() ? 1 : 0);
		out.writeBytes32(state.getOriginTxHash());
		out.writeBytes32(state.getUpdatedByTxHash());
		out.writeBigIntegerScalar(state.getTotalSupply().toBigInteger());
		out.writeLongScalar(state.getUpdatedAtBlockHeight());
		out.writeOptionalLongScalar(
				state.getUpdatedAtTimestamp() != null ? state.getUpdatedAtTimestamp().toEpochMilli() : null);
	}
}
