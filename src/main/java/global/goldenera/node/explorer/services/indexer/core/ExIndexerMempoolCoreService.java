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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExMemTransfer;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerMempoolCoreService {

	JdbcTemplate jdbcTemplate;

	private static final String INSERT_SQL = """
			INSERT INTO explorer_mem_transfer (
			    hash,
			    added_at,
			    transfer_type,
			    from_address,
			    to_address,
			    token_address,
			    amount,
			    tx_type,
			    tx_timestamp,
			    network,
			    version,
			    fee,
			    nonce,
			    tx_size,
			    signature,
			    reference_hash,
			    message,
			    payload_type,
			    raw_payload
			) VALUES (
			    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
			) ON CONFLICT (hash) DO NOTHING
			""";

	private static final String DELETE_SQL = "DELETE FROM explorer_mem_transfer WHERE hash = ?";
	private static final String TRUNCATE_SQL = "TRUNCATE TABLE explorer_mem_transfer";

	@Transactional
	public void batchInsert(List<ExMemTransfer> transfers) {
		if (transfers.isEmpty())
			return;

		jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ExMemTransfer t = transfers.get(i);

				// 1. Hash
				ps.setBytes(1, t.getHash().toArray());
				// 2. Added At
				ps.setTimestamp(2, Timestamp.from(t.getAddedAt()));
				// 3. Transfer Type
				ps.setInt(3, t.getTransferType().getCode());
				// 4. From
				ps.setBytes(4, t.getFrom().toArray());
				// 5. To (Nullable)
				if (t.getTo() != null)
					ps.setBytes(5, t.getTo().toArray());
				else
					ps.setNull(5, Types.BINARY);
				// 6. Token Address (Nullable)
				if (t.getTokenAddress() != null)
					ps.setBytes(6, t.getTokenAddress().toArray());
				else
					ps.setNull(6, Types.BINARY);
				// 7. Amount (Nullable)
				if (t.getAmount() != null)
					ps.setBigDecimal(7, new java.math.BigDecimal(t.getAmount().toBigInteger()));
				else
					ps.setNull(7, Types.NUMERIC);

				// --- RAW DATA ---
				// 8. Tx Type
				ps.setInt(8, t.getTxType().getCode());
				// 9. Tx Timestamp
				ps.setTimestamp(9, Timestamp.from(t.getTxTimestamp()));
				// 10. Network
				ps.setInt(10, t.getNetwork().getCode());
				// 11. Version
				ps.setInt(11, t.getVersion().getCode());
				// 12. Fee
				ps.setBigDecimal(12, new java.math.BigDecimal(t.getFee().toBigInteger()));
				// 13. Nonce
				ps.setLong(13, t.getNonce());
				// 14. Size
				ps.setInt(14, t.getSize());
				// 15. Signature
				if (t.getSignature() != null)
					ps.setBytes(15, t.getSignature().toArray());
				else
					ps.setNull(15, Types.BINARY);
				// 16. Reference Hash
				if (t.getReferenceHash() != null)
					ps.setBytes(16, t.getReferenceHash().toArray());
				else
					ps.setNull(16, Types.BINARY);
				// 17. Message
				if (t.getMessage() != null && !t.getMessage().isEmpty())
					ps.setBytes(17, t.getMessage().toArray());
				else
					ps.setNull(17, Types.BINARY);

				// --- PAYLOAD ---
				// 18. Payload Type
				if (t.getPayloadType() != null)
					ps.setInt(18, t.getPayloadType().getCode());
				else
					ps.setNull(18, Types.INTEGER);
				// 19. Raw Payload
				if (t.getRawPayload() != null && !t.getRawPayload().isEmpty())
					ps.setBytes(19, t.getRawPayload().toArray());
				else
					ps.setNull(19, Types.BINARY);
			}

			@Override
			public int getBatchSize() {
				return transfers.size();
			}
		});
	}

	@Transactional
	public void batchDelete(List<Hash> hashes) {
		if (hashes.isEmpty())
			return;
		jdbcTemplate.batchUpdate(DELETE_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ps.setBytes(1, hashes.get(i).toArray());
			}

			@Override
			public int getBatchSize() {
				return hashes.size();
			}
		});
	}

	@Transactional
	public void truncate() {
		jdbcTemplate.execute(TRUNCATE_SQL);
	}
}
