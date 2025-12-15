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
package global.goldenera.node.explorer.services.indexer.core;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.explorer.converters.AddressSetConverter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerConsensusCoreService {

	JdbcTemplate jdbcTemplate;
	AddressSetConverter addressSetConverter = new AddressSetConverter();

	public void bulkUpsertBips(List<Hash> keys, Map<Hash, StateDiff<BipState>> diffs) {
		String sql = """
				INSERT INTO explorer_bip_state (
				    bip_hash, bip_state_version, status, is_action_executed, type, number_of_required_votes,
				    approvers, disapprovers, expiration_timestamp, executed_at_timestamp,
				    origin_tx_hash, updated_by_tx_hash, created_at_block_height, created_at_timestamp,
				    updated_at_block_height, updated_at_timestamp,
				    meta_version, meta_tx_version, meta_derived_token_address, meta_tx_payload_raw
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (bip_hash) DO UPDATE SET
				    -- Mutable fields (State)
				    status = EXCLUDED.status,
				    approvers = EXCLUDED.approvers,
				    disapprovers = EXCLUDED.disapprovers,
				    updated_at_block_height = EXCLUDED.updated_at_block_height,
				    updated_at_timestamp = EXCLUDED.updated_at_timestamp,
				    updated_by_tx_hash = EXCLUDED.updated_by_tx_hash,
				    is_action_executed = EXCLUDED.is_action_executed,
				    executed_at_timestamp = EXCLUDED.executed_at_timestamp,
				    bip_state_version = EXCLUDED.bip_state_version
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Hash hash = keys.get(i);
				BipState state = diffs.get(hash).getNewValue();

				ps.setBytes(1, hash.toArray());
				ps.setInt(2, state.getVersion().getCode());
				ps.setInt(3, state.getStatus().getCode());
				ps.setBoolean(4, state.isActionExecuted());
				ps.setInt(5, state.getType().getCode());

				ps.setLong(6, state.getNumberOfRequiredVotes());

				// Convert approvers and disapprovers to byte arrays
				ps.setBytes(7, addressSetConverter.convertToDatabaseColumn(state.getApprovers()));
				ps.setBytes(8, addressSetConverter.convertToDatabaseColumn(state.getDisapprovers()));

				ps.setTimestamp(9, Timestamp.from(state.getExpirationTimestamp()));
				ps.setTimestamp(10,
						state.getExecutedAtTimestamp() != null ? Timestamp.from(state.getExecutedAtTimestamp()) : null);
				ps.setBytes(11, state.getOriginTxHash().toArray());
				ps.setBytes(12, state.getUpdatedByTxHash() != null ? state.getUpdatedByTxHash().toArray() : null);
				ps.setLong(13, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(14, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setLong(15, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(16, Timestamp.from(state.getUpdatedAtTimestamp()));

				// Metadata fields
				if (state.getMetadata() != null) {
					// 17. meta_version (Enum -> Int)
					ps.setInt(17, state.getMetadata().getVersion().getCode());

					// 18. meta_tx_version (Enum -> Int)
					ps.setInt(18, state.getMetadata().getTxVersion().getCode());

					// 19. derived address
					ps.setBytes(19,
							state.getMetadata().getDerivedTokenAddress() != null
									? state.getMetadata().getDerivedTokenAddress().toArray()
									: null);

					// 20. payload raw
					byte[] rawMeta = null;
					if (state.getMetadata().getTxPayload() != null) {
						rawMeta = TxPayloadEncoder.INSTANCE
								.encode(state.getMetadata().getTxPayload(), state.getMetadata().getTxVersion())
								.toArray();
					}
					ps.setBytes(20, rawMeta);
				} else {
					ps.setNull(17, java.sql.Types.INTEGER);
					ps.setNull(18, java.sql.Types.INTEGER);
					ps.setNull(19, java.sql.Types.BINARY);
					ps.setNull(20, java.sql.Types.BINARY);
				}
			}

			@Override
			public int getBatchSize() {
				return keys.size();
			}
		});
	}

	public void upsertNetworkParams(NetworkParamsState state) {
		String sql = """
				    INSERT INTO explorer_network_params (
				        id,
				        network_params_version,
				        block_reward,
				        block_reward_pool_address,
				        target_mining_time_ms,
				        asert_half_life_blocks,
				        asert_anchor_height,
				        min_difficulty,
				        min_tx_base_fee,
				        min_tx_byte_fee,
				        updated_by_tx_hash,
				        current_authority_count,
				        current_validator_count,
				        updated_at_block_height,
				        updated_at_timestamp
				    ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				    ON CONFLICT (id) DO UPDATE SET
				        network_params_version = EXCLUDED.network_params_version,
				        block_reward = EXCLUDED.block_reward,
				        block_reward_pool_address = EXCLUDED.block_reward_pool_address,
				        target_mining_time_ms = EXCLUDED.target_mining_time_ms,
				        asert_half_life_blocks = EXCLUDED.asert_half_life_blocks,
				        asert_anchor_height = EXCLUDED.asert_anchor_height,
				        min_difficulty = EXCLUDED.min_difficulty,
				        min_tx_base_fee = EXCLUDED.min_tx_base_fee,
				        min_tx_byte_fee = EXCLUDED.min_tx_byte_fee,
				        updated_by_tx_hash = EXCLUDED.updated_by_tx_hash,
				        current_authority_count = EXCLUDED.current_authority_count,
				        current_validator_count = EXCLUDED.current_validator_count,
				        updated_at_block_height = EXCLUDED.updated_at_block_height,
				        updated_at_timestamp = EXCLUDED.updated_at_timestamp
				""";

		jdbcTemplate.update(sql,
				// 1. Version (Enum -> Int)
				state.getVersion().getCode(),

				// 2. Block Reward
				new BigDecimal(state.getBlockReward().toBigInteger()),

				// 3. Pool Address
				state.getBlockRewardPoolAddress().toArray(),

				// 4. Target Time
				state.getTargetMiningTimeMs(),

				// 5. Asert Half Life
				state.getAsertHalfLifeBlocks(),

				// 6. Asert Anchor
				state.getAsertAnchorHeight(),

				// 7. Min Difficulty
				new BigDecimal(state.getMinDifficulty()),

				// 8. Base Fee
				new BigDecimal(state.getMinTxBaseFee().toBigInteger()),

				// 9. Byte Fee
				new BigDecimal(state.getMinTxByteFee().toBigInteger()),

				// 10. Updated By Tx
				state.getUpdatedByTxHash().toArray(),

				// 11. Authority Count
				state.getCurrentAuthorityCount(),

				// 12. Validator Count
				state.getCurrentValidatorCount(),

				// 13. Updated Height
				state.getUpdatedAtBlockHeight(),

				// 14. Updated Time
				Timestamp.from(state.getUpdatedAtTimestamp()));
	}

	public void bulkUpsertAliases(Map<String, AddressAliasState> adds) {
		String sql = "INSERT INTO explorer_address_alias (alias, address, origin_tx_hash, created_at_block_height, created_at_timestamp, address_alias_version) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (alias) DO NOTHING";
		List<Map.Entry<String, AddressAliasState>> list = new ArrayList<>(adds.entrySet());
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Map.Entry<String, AddressAliasState> entry = list.get(i);
				AddressAliasState s = entry.getValue();
				ps.setString(1, entry.getKey());
				ps.setBytes(2, s.getAddress().toArray());
				ps.setBytes(3, s.getOriginTxHash().toArray());
				ps.setLong(4, s.getCreatedAtBlockHeight());
				ps.setTimestamp(5, Timestamp.from(s.getCreatedAtTimestamp()));
				ps.setInt(6, s.getVersion().getCode());
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}

	public void bulkDeleteAliases(Set<String> aliases) {
		if (aliases.isEmpty())
			return;
		String sql = "DELETE FROM explorer_address_alias WHERE alias = ?";
		List<String> list = new ArrayList<>(aliases);
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ps.setString(1, list.get(i));
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}

	public void bulkDeleteAuthorities(Set<Address> addresses) {
		if (addresses.isEmpty())
			return;
		String sql = "DELETE FROM explorer_authority WHERE address = ?";
		List<Address> list = new ArrayList<>(addresses);
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ps.setBytes(1, list.get(i).toArray());
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}

	public void bulkUpsertAuthorities(Map<Address, AuthorityState> adds) {
		String sql = "INSERT INTO explorer_authority (address, origin_tx_hash, created_at_block_height, created_at_timestamp, authority_version) VALUES (?, ?, ?, ?, ?) ON CONFLICT (address) DO NOTHING";
		List<Map.Entry<Address, AuthorityState>> list = new ArrayList<>(adds.entrySet());
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Map.Entry<Address, AuthorityState> entry = list.get(i);
				AuthorityState s = entry.getValue();
				ps.setBytes(1, entry.getKey().toArray());
				ps.setBytes(2, s.getOriginTxHash().toArray());
				ps.setLong(3, s.getCreatedAtBlockHeight());
				ps.setTimestamp(4, Timestamp.from(s.getCreatedAtTimestamp()));
				ps.setInt(5, s.getVersion().getCode());
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}

	public void bulkDeleteValidators(Set<Address> addresses) {
		if (addresses.isEmpty())
			return;
		String sql = "DELETE FROM explorer_validator WHERE address = ?";
		List<Address> list = new ArrayList<>(addresses);
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ps.setBytes(1, list.get(i).toArray());
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}

	public void bulkUpsertValidators(Map<Address, ValidatorState> adds) {
		String sql = "INSERT INTO explorer_validator (address, origin_tx_hash, created_at_block_height, created_at_timestamp, validator_version) VALUES (?, ?, ?, ?, ?) ON CONFLICT (address) DO NOTHING";
		List<Map.Entry<Address, ValidatorState>> list = new ArrayList<>(adds.entrySet());
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Map.Entry<Address, ValidatorState> entry = list.get(i);
				ValidatorState s = entry.getValue();
				ps.setBytes(1, entry.getKey().toArray());
				ps.setBytes(2, s.getOriginTxHash().toArray());
				ps.setLong(3, s.getCreatedAtBlockHeight());
				ps.setTimestamp(4, Timestamp.from(s.getCreatedAtTimestamp()));
				ps.setInt(5, s.getVersion().getCode());
			}

			public int getBatchSize() {
				return list.size();
			}
		});
	}
}
