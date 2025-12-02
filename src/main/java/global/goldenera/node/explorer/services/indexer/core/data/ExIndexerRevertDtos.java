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
package global.goldenera.node.explorer.services.indexer.core.data;

import java.math.BigDecimal;
import java.time.Instant;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonProperty;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.state.AccountBalanceStateVersion;
import global.goldenera.node.shared.enums.state.AccountNonceStateVersion;
import global.goldenera.node.shared.enums.state.AddressAliasStateVersion;
import global.goldenera.node.shared.enums.state.AuthorityStateVersion;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import global.goldenera.node.shared.enums.state.TokenStateVersion;

public class ExIndexerRevertDtos {

	public static record BalanceRevertDto(
			@JsonProperty("b") BigDecimal balance,
			@JsonProperty("uh") long updatedAtBlockHeight,
			@JsonProperty("ut") Instant updatedAtTimestamp,
			@JsonProperty("ver") int version) {
		public static BalanceRevertDto from(Wei balance, long uh, Instant ut, AccountBalanceStateVersion v) {
			return new BalanceRevertDto(new BigDecimal(balance.toBigInteger()), uh, ut, v.getCode());
		}
	}

	public static record NonceRevertDto(
			@JsonProperty("n") long nonce,
			@JsonProperty("uh") long updatedAtBlockHeight,
			@JsonProperty("ut") Instant updatedAtTimestamp,
			@JsonProperty("ver") int version) {
		public static NonceRevertDto from(long nonce, long uh, Instant ut, AccountNonceStateVersion v) {
			return new NonceRevertDto(nonce, uh, ut, v.getCode());
		}
	}

	public static record TokenRevertDto(
			@JsonProperty("ts") BigDecimal totalSupply,
			@JsonProperty("uh") long updatedAtBlockHeight,
			@JsonProperty("ut") Instant updatedAtTimestamp,
			@JsonProperty("name") String name,
			@JsonProperty("sun") String smallestUnitName,
			@JsonProperty("logo") String logoUrl,
			@JsonProperty("web") String websiteUrl,
			@JsonProperty("utx") String updatedByTxHashHex,
			@JsonProperty("ver") int version) {
		public static TokenRevertDto from(Wei ts, long uh, Instant ut, String name, String smallestUnitName,
				String logo, String websiteUrl, Hash updatedByTxHash, TokenStateVersion v) {
			return new TokenRevertDto(
					new BigDecimal(ts.toBigInteger()),
					uh,
					ut,
					name,
					smallestUnitName,
					logo,
					websiteUrl,
					updatedByTxHash != null ? updatedByTxHash.toHexString() : null,
					v.getCode());
		}
	}

	public static record AliasRevertDto(
			@JsonProperty("addr") String addressHex,
			@JsonProperty("tx") String originTxHex,
			@JsonProperty("ch") long createdAtBlockHeight,
			@JsonProperty("ct") Instant createdAtTimestamp,
			@JsonProperty("ver") int version) {
		public static AliasRevertDto from(Address address, Hash originTx, long ch, Instant ct,
				AddressAliasStateVersion v) {
			return new AliasRevertDto(address.toHexString(), originTx.toHexString(), ch, ct, v.getCode());
		}
	}

	public static record AuthorityRevertDto(
			@JsonProperty("tx") String originTxHex,
			@JsonProperty("ch") long createdAtBlockHeight,
			@JsonProperty("ct") Instant createdAtTimestamp,
			@JsonProperty("ver") int version) {
		public static AuthorityRevertDto from(Hash originTx, long ch, Instant ct, AuthorityStateVersion v) {
			return new AuthorityRevertDto(originTx.toHexString(), ch, ct, v.getCode());
		}
	}

	public record NetworkParamsRevertDto(
			@JsonProperty("ver") int version,
			@JsonProperty("br") BigDecimal blockReward,
			@JsonProperty("pool") String blockRewardPoolAddressHex,
			@JsonProperty("tgt") long targetMiningTimeMs,
			@JsonProperty("half") long asertHalfLifeBlocks,
			@JsonProperty("anch") long asertAnchorHeight,
			@JsonProperty("diff") BigDecimal minDifficulty,
			@JsonProperty("base") BigDecimal minTxBaseFee,
			@JsonProperty("byte") BigDecimal minTxByteFee,
			@JsonProperty("auth_cnt") long currentAuthorityCount,
			@JsonProperty("utx") String updatedByTxHashHex,
			@JsonProperty("uh") long updatedAtBlockHeight,
			@JsonProperty("ut") Instant updatedAtTimestamp) {
		public static NetworkParamsRevertDto from(NetworkParamsState state) {
			return new NetworkParamsRevertDto(
					state.getVersion().getCode(),
					new BigDecimal(state.getBlockReward().toBigInteger()),
					state.getBlockRewardPoolAddress().toHexString(),
					state.getTargetMiningTimeMs(),
					state.getAsertHalfLifeBlocks(),
					state.getAsertAnchorHeight(),
					new BigDecimal(state.getMinDifficulty()),
					new BigDecimal(state.getMinTxBaseFee().toBigInteger()),
					new BigDecimal(state.getMinTxByteFee().toBigInteger()),
					state.getCurrentAuthorityCount(),
					state.getUpdatedByTxHash().toHexString(),
					state.getUpdatedAtBlockHeight(),
					state.getUpdatedAtTimestamp());
		}
	}

	public static record BipRevertDto(
			@JsonProperty("s") int status,
			@JsonProperty("uh") long updatedAtBlockHeight,
			@JsonProperty("ut") Instant updatedAtTimestamp,
			@JsonProperty("utx") String updatedByTxHashHex,
			@JsonProperty("appr") String approversHex,
			@JsonProperty("disappr") String disapproversHex,
			@JsonProperty("exec") boolean isActionExecuted,
			@JsonProperty("exect") Instant executedAtTimestamp,
			@JsonProperty("ver") int version) {
		public static BipRevertDto from(BipStatus status,
				long updatedAtBlockHeight, Instant updatedAtTimestamp, Hash updatedByTxHash,
				Bytes approvers, Bytes disapprovers,
				boolean isActionExecuted, Instant executedAtTimestamp,
				BipStateVersion v) {
			return new BipRevertDto(
					status.getCode(),
					updatedAtBlockHeight, updatedAtTimestamp,
					updatedByTxHash != null ? updatedByTxHash.toHexString() : null,
					approvers != null ? approvers.toHexString() : null,
					disapprovers != null ? disapprovers.toHexString() : null,
					isActionExecuted,
					executedAtTimestamp,
					v.getCode());
		}
	}
}