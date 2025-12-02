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
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.shared.consensus.state.StateDiff;
import global.goldenera.node.shared.consensus.state.TokenState;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerTokenCoreService {

	JdbcTemplate jdbcTemplate;

	public void bulkUpsertTokens(List<Address> keys, Map<Address, StateDiff<TokenState>> diffs) {
		String sql = """
				INSERT INTO explorer_token (
				    address, name, smallest_unit_name, number_of_decimals, website_url, logo_url, max_supply,
				    total_supply, origin_tx_hash, updated_by_tx_hash, created_at_block_height, created_at_timestamp,
				    updated_at_block_height, updated_at_timestamp, user_burnable, token_state_version
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (address) DO UPDATE SET
				    total_supply = EXCLUDED.total_supply,
				    updated_by_tx_hash = EXCLUDED.updated_by_tx_hash,
				    updated_at_block_height = EXCLUDED.updated_at_block_height,
				    updated_at_timestamp = EXCLUDED.updated_at_timestamp,
				    name = EXCLUDED.name,
				    smallest_unit_name = EXCLUDED.smallest_unit_name,
				    logo_url = EXCLUDED.logo_url,
					website_url = EXCLUDED.website_url,
					token_state_version = EXCLUDED.token_state_version
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				TokenState state = diffs.get(keys.get(i)).getNewValue();
				ps.setBytes(1, keys.get(i).toArray());
				ps.setString(2, state.getName());
				ps.setString(3, state.getSmallestUnitName());
				ps.setInt(4, state.getNumberOfDecimals());
				ps.setString(5, state.getWebsiteUrl());
				ps.setString(6, state.getLogoUrl());
				ps.setBigDecimal(7, state.getMaxSupply() != null ? new BigDecimal(state.getMaxSupply()) : null);
				ps.setBigDecimal(8, new BigDecimal(state.getTotalSupply().toBigInteger()));
				ps.setBytes(9, state.getOriginTxHash().toArray());
				ps.setBytes(10, state.getUpdatedByTxHash().toArray());
				ps.setLong(11, state.getUpdatedAtBlockHeight()); // Creation same as update for new
				ps.setTimestamp(12, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setLong(13, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(14, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setBoolean(15, state.isUserBurnable());
				ps.setInt(16, state.getVersion().getCode());
			}

			@Override
			public int getBatchSize() {
				return keys.size();
			}
		});
	}
}
