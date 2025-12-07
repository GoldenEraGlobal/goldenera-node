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
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.shared.datatypes.BalanceKey;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerAccountCoreService {

	JdbcTemplate jdbcTemplate;

	/*
	 * BULK Upserts for indexer
	 */
	@Transactional(rollbackFor = Exception.class)
	public void bulkUpsertBalances(List<BalanceKey> keys, Map<BalanceKey, StateDiff<AccountBalanceState>> diffs) {
		String sql = """
				INSERT INTO explorer_account_balance (
				    address, token_address, balance,
				    created_at_block_height, created_at_timestamp,
				    updated_at_block_height, updated_at_timestamp,
				    account_balance_version
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (address, token_address) DO UPDATE SET
				    balance = EXCLUDED.balance,
				    updated_at_block_height = EXCLUDED.updated_at_block_height,
				    updated_at_timestamp = EXCLUDED.updated_at_timestamp,
					account_balance_version = EXCLUDED.account_balance_version
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				BalanceKey key = keys.get(i);
				AccountBalanceState state = diffs.get(key).getNewValue();

				ps.setBytes(1, key.getAddress().toArray());
				ps.setBytes(2, key.getTokenAddress().toArray());
				ps.setBigDecimal(3, new BigDecimal(state.getBalance().toBigInteger()));

				ps.setLong(4, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(5, Timestamp.from(state.getUpdatedAtTimestamp()));

				ps.setLong(6, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(7, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setInt(8, state.getVersion().getCode());
			}

			@Override
			public int getBatchSize() {
				return keys.size();
			}
		});
	}

	@Transactional(rollbackFor = Exception.class)
	public void bulkUpsertNonces(List<Address> keys, Map<Address, StateDiff<AccountNonceState>> diffs) {
		String sql = """
				INSERT INTO explorer_account_nonce (
				    address, nonce, created_at_block_height, created_at_timestamp, updated_at_block_height, updated_at_timestamp, account_nonce_version
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (address) DO UPDATE SET
				    nonce = EXCLUDED.nonce,
				    updated_at_block_height = EXCLUDED.updated_at_block_height,
				    updated_at_timestamp = EXCLUDED.updated_at_timestamp,
					account_nonce_version = EXCLUDED.account_nonce_version
				""";
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Address addr = keys.get(i);
				AccountNonceState state = diffs.get(addr).getNewValue();
				ps.setBytes(1, addr.toArray());
				ps.setLong(2, state.getNonce());
				ps.setLong(3, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(4, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setLong(5, state.getUpdatedAtBlockHeight());
				ps.setTimestamp(6, Timestamp.from(state.getUpdatedAtTimestamp()));
				ps.setInt(7, state.getVersion().getCode());
			}

			@Override
			public int getBatchSize() {
				return keys.size();
			}
		});
	}
}
