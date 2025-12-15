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
package global.goldenera.node.explorer.entities;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.explorer.converters.state.NetworkParamsStateVersionConverter;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.HashConverter;
import global.goldenera.node.shared.converters.WeiConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_network_params", indexes = {
		@Index(name = "idx_explorer_network_params_updated_by_tx_hash", columnList = "updated_by_tx_hash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "id")
public class ExNetworkParams implements NetworkParamsState {

	@Id
	@Column(name = "id")
	Integer id = 1;

	@Column(name = "network_params_version", columnDefinition = "INTEGER", updatable = true, nullable = false)
	@Convert(converter = NetworkParamsStateVersionConverter.class)
	NetworkParamsStateVersion version;

	@Column(name = "block_reward", precision = 80, scale = 0, columnDefinition = "NUMERIC", updatable = true, nullable = false)
	@Convert(converter = WeiConverter.class)
	Wei blockReward;

	@Column(name = "block_reward_pool_address", length = 32, columnDefinition = "BYTEA", updatable = true, nullable = false)
	@Convert(converter = AddressConverter.class)
	Address blockRewardPoolAddress;

	@Column(name = "target_mining_time_ms", updatable = true, nullable = false)
	long targetMiningTimeMs;
	@Column(name = "asert_half_life_blocks", updatable = true, nullable = false)
	long asertHalfLifeBlocks;
	@Column(name = "asert_anchor_height", updatable = true, nullable = false)
	long asertAnchorHeight;

	@Column(name = "min_difficulty", precision = 80, scale = 0, columnDefinition = "NUMERIC", updatable = true, nullable = false)
	BigInteger minDifficulty;

	@Column(name = "min_tx_base_fee", precision = 80, scale = 0, columnDefinition = "NUMERIC", updatable = true, nullable = false)
	@Convert(converter = WeiConverter.class)
	Wei minTxBaseFee;

	@Column(name = "min_tx_byte_fee", precision = 80, scale = 0, columnDefinition = "NUMERIC", updatable = true, nullable = false)
	@Convert(converter = WeiConverter.class)
	Wei minTxByteFee;

	@Column(name = "updated_by_tx_hash", length = 32, columnDefinition = "BYTEA", updatable = true, nullable = false)
	@Convert(converter = HashConverter.class)
	Hash updatedByTxHash;

	@Column(name = "current_authority_count", updatable = true, nullable = false)
	long currentAuthorityCount;

	@Column(name = "current_validator_count", updatable = true, nullable = false)
	long currentValidatorCount;

	@Column(name = "updated_at_block_height", updatable = true, nullable = false)
	long updatedAtBlockHeight;

	@Column(name = "updated_at_timestamp", updatable = true, nullable = false)
	Instant updatedAtTimestamp;
}
