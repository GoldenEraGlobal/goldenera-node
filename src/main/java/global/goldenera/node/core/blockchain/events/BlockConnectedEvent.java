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
package global.goldenera.node.core.blockchain.events;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.context.ApplicationEvent;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.shared.datatypes.BalanceKey;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = PRIVATE)
public class BlockConnectedEvent extends ApplicationEvent {

	@NonNull
	final ConnectedSource connectedSource;
	@NonNull
	final Block block;

	final Map<BalanceKey, StateDiff<AccountBalanceState>> balanceDiffs;
	final Map<Address, StateDiff<AccountNonceState>> nonceDiffs;
	final Map<Address, StateDiff<TokenState>> tokenDiffs;
	final Map<Hash, StateDiff<BipState>> bipDiffs;

	final StateDiff<NetworkParamsState> networkParamsDiff;

	final Map<Address, AuthorityState> authoritiesToAdd;
	final Map<Address, AuthorityState> authoritiesToRemove;

	final Map<Address, ValidatorState> validatorsToAdd;
	final Map<Address, ValidatorState> validatorsToRemove;

	final Map<String, AddressAliasState> addressAliasesToAdd;
	final Map<String, AddressAliasState> addressAliasesToRemove;

	Address receivedFrom;
	Instant receivedAt;

	final Wei minerTotalFees;
	final Wei minerActualRewardPaid;

	final BigInteger cumulativeDifficulty;

	final Map<Hash, Wei> actualBurnAmounts;

	final List<BlockEvent> events;

	/** True only when a following batch-boundary event owns head-only/coalesced work. */
	final boolean batchMember;

	public BlockConnectedEvent(
			Object source,
			ConnectedSource connectedSource,
			Block block,
			Map<BalanceKey, StateDiff<AccountBalanceState>> balanceDiffs,
			Map<Address, StateDiff<AccountNonceState>> nonceDiffs,
			Map<Address, StateDiff<TokenState>> tokenDiffs,
			Map<Hash, StateDiff<BipState>> bipDiffs,
			StateDiff<NetworkParamsState> networkParamsDiff,
			Map<Address, AuthorityState> authoritiesToAdd,
			Map<Address, AuthorityState> authoritiesToRemove,
			Map<Address, ValidatorState> validatorsToAdd,
			Map<Address, ValidatorState> validatorsToRemove,
			Map<String, AddressAliasState> addressAliasesToAdd,
			Map<String, AddressAliasState> addressAliasesToRemove,
			Wei totalFees,
			Wei actualRewardPaid,
			BigInteger cumulativeDifficulty,
			Map<Hash, Wei> actualBurnAmounts,
			List<BlockEvent> events,
			Address receivedFrom,
			Instant receivedAt) {
		this(source, connectedSource, block, balanceDiffs, nonceDiffs, tokenDiffs, bipDiffs,
				networkParamsDiff, authoritiesToAdd, authoritiesToRemove, validatorsToAdd,
				validatorsToRemove, addressAliasesToAdd, addressAliasesToRemove, totalFees,
				actualRewardPaid, cumulativeDifficulty, actualBurnAmounts, events, receivedFrom,
				receivedAt, false);
	}

	public BlockConnectedEvent(
			Object source,
			ConnectedSource connectedSource,
			Block block,
			Map<BalanceKey, StateDiff<AccountBalanceState>> balanceDiffs,
			Map<Address, StateDiff<AccountNonceState>> nonceDiffs,
			Map<Address, StateDiff<TokenState>> tokenDiffs,
			Map<Hash, StateDiff<BipState>> bipDiffs,
			StateDiff<NetworkParamsState> networkParamsDiff,
			Map<Address, AuthorityState> authoritiesToAdd,
			Map<Address, AuthorityState> authoritiesToRemove,
			Map<Address, ValidatorState> validatorsToAdd,
			Map<Address, ValidatorState> validatorsToRemove,
			Map<String, AddressAliasState> addressAliasesToAdd,
			Map<String, AddressAliasState> addressAliasesToRemove,
			Wei totalFees,
			Wei actualRewardPaid,
			BigInteger cumulativeDifficulty,
			Map<Hash, Wei> actualBurnAmounts,
			List<BlockEvent> events,
			Address receivedFrom,
			Instant receivedAt,
			boolean batchMember) {
		super(source);
		this.connectedSource = connectedSource;
		this.block = block;
		this.balanceDiffs = immutableMap(balanceDiffs);
		this.nonceDiffs = immutableMap(nonceDiffs);
		this.tokenDiffs = immutableMap(tokenDiffs);
		this.bipDiffs = immutableMap(bipDiffs);
		this.networkParamsDiff = networkParamsDiff;
		this.authoritiesToAdd = immutableMap(authoritiesToAdd);
		this.authoritiesToRemove = immutableMap(authoritiesToRemove);
		this.validatorsToAdd = immutableMap(validatorsToAdd);
		this.validatorsToRemove = immutableMap(validatorsToRemove);
		this.addressAliasesToAdd = immutableMap(addressAliasesToAdd);
		this.addressAliasesToRemove = immutableMap(addressAliasesToRemove);
		this.minerTotalFees = totalFees;
		this.minerActualRewardPaid = actualRewardPaid;
		this.cumulativeDifficulty = cumulativeDifficulty;
		this.actualBurnAmounts = immutableMap(actualBurnAmounts);
		this.events = events == null ? List.of() : List.copyOf(events);
		this.receivedFrom = receivedFrom;
		this.receivedAt = receivedAt;
		this.batchMember = batchMember;
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
		return values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
	}

	@AllArgsConstructor
	@FieldDefaults(level = PRIVATE, makeFinal = true)
	@Getter
	public enum ConnectedSource {
		GENESIS(0), MINER(1), BROADCAST(2), SYNC(3), REORG(4);

		int code;

		public static ConnectedSource fromCode(int code) {
			for (ConnectedSource source : values()) {
				if (source.getCode() == code) {
					return source;
				}
			}
			throw new GEFailedException("Invalid ConnectedSource code: " + code);
		}
	}
}
