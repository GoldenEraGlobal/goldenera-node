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
package global.goldenera.node.bridge.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ge.general.postgresql-enable", havingValue = "true")
public class BridgeReorgPendingStore {

	private final JdbcTemplate jdbcTemplate;

	public ReadyReadd markCanonicalReverted(Hash txHash, long revertSequence) {
		Instant now = Instant.now();
		jdbcTemplate.update("""
				INSERT INTO bridge_reorg_pending
				    (tx_hash, canonical_reverted, revert_sequence, updated_at)
				VALUES (?, TRUE, ?, ?)
				ON CONFLICT (tx_hash) DO UPDATE SET
				    canonical_reverted = TRUE,
				    revert_sequence = GREATEST(bridge_reorg_pending.revert_sequence, EXCLUDED.revert_sequence),
				    updated_at = EXCLUDED.updated_at
				""", txHash.toArray(), revertSequence, Timestamp.from(now));
		return findReady(txHash);
	}

	public ReadyReadd markReadded(MempoolEntry entry, BridgeSourcePosition position) {
		Instant now = Instant.now();
		jdbcTemplate.update("""
				INSERT INTO bridge_reorg_pending
				    (tx_hash, canonical_reverted, readd_epoch, readd_sequence, readd_event_key, raw_tx,
				     first_seen_height, first_seen_at, updated_at)
				VALUES (?, FALSE, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (tx_hash) DO UPDATE SET
				    readd_epoch = EXCLUDED.readd_epoch,
				    readd_sequence = GREATEST(bridge_reorg_pending.readd_sequence, EXCLUDED.readd_sequence),
				    readd_event_key = EXCLUDED.readd_event_key,
				    raw_tx = EXCLUDED.raw_tx,
				    first_seen_height = EXCLUDED.first_seen_height,
				    first_seen_at = EXCLUDED.first_seen_at,
				    updated_at = EXCLUDED.updated_at
				""",
				entry.getHash().toArray(),
				position.epoch(),
				position.sequence(),
				position.eventKey(),
				TxEncoder.INSTANCE.encode(entry.getTx(), true).toArray(),
				entry.getFirstSeenHeight(),
				Timestamp.from(entry.getFirstSeenTime()),
				Timestamp.from(now));
		return findReady(entry.getHash());
	}

	public void delete(Hash txHash) {
		jdbcTemplate.update("DELETE FROM bridge_reorg_pending WHERE tx_hash = ?", txHash.toArray());
	}

	public boolean hasCanonicalRevert(Hash txHash) {
		Boolean pending = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM bridge_reorg_pending
				    WHERE tx_hash = ? AND canonical_reverted = TRUE
				)
				""", Boolean.class, txHash.toArray());
		return Boolean.TRUE.equals(pending);
	}

	private ReadyReadd findReady(Hash txHash) {
		return jdbcTemplate.query("""
				SELECT raw_tx, first_seen_height, first_seen_at,
				       readd_epoch, readd_sequence, readd_event_key
				FROM bridge_reorg_pending
				WHERE tx_hash = ?
				  AND canonical_reverted = TRUE
				  AND raw_tx IS NOT NULL
				  AND readd_epoch IS NOT NULL
				  AND readd_sequence IS NOT NULL
				  AND readd_event_key IS NOT NULL
				""", this::mapReady, txHash.toArray()).stream().findFirst().orElse(null);
	}

	private ReadyReadd mapReady(ResultSet resultSet, int rowNumber) throws SQLException {
		Tx tx = TxDecoder.INSTANCE.decode(Bytes.wrap(resultSet.getBytes("raw_tx")));
		MempoolEntry entry = new MempoolEntry(
				tx,
				resultSet.getTimestamp("first_seen_at").toInstant(),
				resultSet.getLong("first_seen_height"),
				null);
		return new ReadyReadd(entry, new BridgeSourcePosition(
				resultSet.getObject("readd_epoch", UUID.class),
				resultSet.getLong("readd_sequence"),
				resultSet.getObject("readd_event_key", UUID.class)));
	}

	public record ReadyReadd(MempoolEntry entry, BridgeSourcePosition position) {
	}
}
