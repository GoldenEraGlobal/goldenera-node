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
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.explorer.entities.ExTransfer;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerBlockDataCoreService {

	JdbcTemplate jdbcTemplate;

	public void insertBlockHeader(
			Block block,
			BigInteger cumulativeDifficulty,
			BigInteger minerTotalFees,
			BigInteger minerActualRewardPaid) {
		BlockHeader header = block.getHeader();
		String sql = """
				    INSERT INTO explorer_block_header (
				        hash, height, block_version, timestamp, previous_hash,
				        tx_root_hash, state_root_hash, difficulty, coinbase, nonce, signature, identity,
				        block_size, cumulative_difficulty, number_of_txs, total_fees, block_reward
				    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";

		jdbcTemplate.update(sql,
				header.getHash().toArray(),
				header.getHeight(),
				header.getVersion().getCode(),
				Timestamp.from(header.getTimestamp()),
				header.getPreviousHash().toArray(),
				header.getTxRootHash().toArray(),
				header.getStateRootHash().toArray(),
				new BigDecimal(header.getDifficulty()),
				header.getCoinbase().toArray(),
				header.getNonce(),
				header.getSignature() != null ? header.getSignature().toArray() : null,
				header.getIdentity() != null ? header.getIdentity().toArray() : null,
				header.getSize(),
				new BigDecimal(cumulativeDifficulty),
				block.getTxs().size(),
				new BigDecimal(minerTotalFees),
				new BigDecimal(minerActualRewardPaid));
	}

	public void insertTransactions(List<Tx> txs, long blockHeight, Hash blockHash) {
		if (txs.isEmpty())
			return;

		String sql = """
				    INSERT INTO explorer_tx (
				        hash, block_height, block_hash, tx_index, timestamp, tx_size,
				        tx_version, type, network, nonce, sender, recipient, amount, fee,
				        token_address, message, reference_hash, signature, payload_type, raw_payload
				    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Tx tx = txs.get(i);
				ps.setBytes(1, tx.getHash().toArray());
				ps.setLong(2, blockHeight);
				ps.setBytes(3, blockHash.toArray());
				ps.setInt(4, i);
				ps.setTimestamp(5, Timestamp.from(tx.getTimestamp()));
				ps.setInt(6, tx.getSize());
				ps.setInt(7, tx.getVersion().getCode());
				ps.setInt(8, tx.getType().getCode());
				ps.setInt(9, tx.getNetwork().getCode());
				ps.setLong(10, tx.getNonce());
				ps.setBytes(11, tx.getSender().toArray());
				ps.setBytes(12, tx.getRecipient() != null ? tx.getRecipient().toArray() : null);
				ps.setBigDecimal(13, tx.getAmount() != null ? new BigDecimal(tx.getAmount().toBigInteger()) : null);
				ps.setBigDecimal(14, new BigDecimal(tx.getFee().toBigInteger()));
				ps.setBytes(15, tx.getTokenAddress() != null ? tx.getTokenAddress().toArray() : null);
				ps.setBytes(16, tx.getMessage() != null ? tx.getMessage().toArray() : null);
				ps.setBytes(17, tx.getReferenceHash() != null ? tx.getReferenceHash().toArray() : null);
				ps.setBytes(18, tx.getSignature() != null ? tx.getSignature().toArray() : null);
				ps.setObject(19, tx.getPayload() != null ? tx.getPayload().getPayloadType().getCode() : null);
				ps.setBytes(20,
						tx.getPayload() != null
								? TxPayloadEncoder.INSTANCE.encode(tx.getPayload(), tx.getVersion()).toArray()
								: null);
			}

			@Override
			public int getBatchSize() {
				return txs.size();
			}
		});
	}

	public void insertTransfers(List<ExTransfer> transfers) {
		if (transfers.isEmpty())
			return;

		String sql = """
				    INSERT INTO explorer_transfer (
				        block_height, block_hash, timestamp, tx_hash, tx_index, type,
				        from_address, to_address, token_address, amount, fee, nonce, message
				    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ExTransfer t = transfers.get(i);
				ps.setLong(1, t.getBlockHeight());
				ps.setBytes(2, t.getBlockHash().toArray());
				ps.setTimestamp(3, Timestamp.from(t.getTimestamp()));
				if (t.getTxHash() != null) {
					ps.setBytes(4, t.getTxHash().toArray());
				} else {
					ps.setNull(4, Types.VARBINARY);
				}
				if (t.getTxIndex() != null) {
					ps.setInt(5, t.getTxIndex());
				} else {
					ps.setNull(5, Types.INTEGER);
				}
				ps.setInt(6, t.getType().getCode());
				if (t.getFrom() != null) {
					ps.setBytes(7, t.getFrom().toArray());
				} else {
					ps.setNull(7, Types.VARBINARY);
				}
				ps.setBytes(8, t.getTo() != null ? t.getTo().toArray() : null);
				ps.setBytes(9, t.getTokenAddress() != null ? t.getTokenAddress().toArray() : null);
				ps.setBigDecimal(10, t.getAmount() != null ? new BigDecimal(t.getAmount().toBigInteger()) : null);
				ps.setBigDecimal(11, t.getFee() != null ? new BigDecimal(t.getFee().toBigInteger()) : null);
				if (t.getNonce() != null) {
					ps.setLong(12, t.getNonce());
				} else {
					ps.setNull(12, Types.BIGINT);
				}
				ps.setBytes(13, t.getMessage() != null ? t.getMessage().toArray() : null);
			}

			@Override
			public int getBatchSize() {
				return transfers.size();
			}
		});
	}
}
