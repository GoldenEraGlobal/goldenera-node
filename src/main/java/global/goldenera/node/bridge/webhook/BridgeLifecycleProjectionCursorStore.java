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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ge.general.postgresql-enable", havingValue = "true")
public class BridgeLifecycleProjectionCursorStore {

	private final JdbcTemplate jdbcTemplate;

	public Cursor current(LifecycleJournalStream stream) {
		List<Cursor> cursors = jdbcTemplate.query(
				"SELECT journal_epoch, last_sequence FROM bridge_lifecycle_cursor WHERE stream = ?",
				(resultSet, rowNumber) -> new Cursor(
						resultSet.getObject("journal_epoch", UUID.class),
						resultSet.getLong("last_sequence")),
				stream.code());
		return cursors.stream().findFirst().orElse(null);
	}

	public void advance(LifecycleJournalStream stream, UUID epoch, long sequence) {
		jdbcTemplate.update("""
				INSERT INTO bridge_lifecycle_cursor (stream, journal_epoch, last_sequence, updated_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (stream) DO UPDATE SET
				    journal_epoch = EXCLUDED.journal_epoch,
				    last_sequence = CASE
				        WHEN bridge_lifecycle_cursor.journal_epoch = EXCLUDED.journal_epoch
				        THEN GREATEST(bridge_lifecycle_cursor.last_sequence, EXCLUDED.last_sequence)
				        ELSE EXCLUDED.last_sequence
				    END,
				    updated_at = EXCLUDED.updated_at
				""", stream.code(), epoch, sequence, Timestamp.from(Instant.now()));
	}

	@Transactional(rollbackFor = Exception.class)
	public Cursor initialize(LifecycleJournalHead head) {
		long sequence = head.floorSequence() - 1L;
		advance(head.stream(), head.epoch(), sequence);
		return new Cursor(head.epoch(), sequence);
	}

	@Transactional(rollbackFor = Exception.class)
	public void reanchor(LifecycleJournalHead head) {
		advance(head.stream(), head.epoch(), head.sequence());
		jdbcTemplate.update("DELETE FROM bridge_reorg_pending");
		if (head.stream() == LifecycleJournalStream.CANONICAL) {
			jdbcTemplate.update("""
					UPDATE bridge_subscription
					SET active_from_canonical_epoch = ?, active_from_canonical_sequence = ?
					WHERE enabled = TRUE
					""", head.epoch(), head.sequence());
		} else {
			jdbcTemplate.update("""
					UPDATE bridge_subscription
					SET active_from_mempool_epoch = ?, active_from_mempool_sequence = ?
					WHERE enabled = TRUE
					""", head.epoch(), head.sequence());
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public boolean recoverFloor(LifecycleJournalHead head, Cursor cursor) {
		boolean recovered = jdbcTemplate.update("""
				UPDATE bridge_lifecycle_cursor
				SET last_sequence = ?, updated_at = ?
				WHERE stream = ? AND journal_epoch = ? AND last_sequence = ?
				""",
				head.floorSequence() - 1L,
				Timestamp.from(Instant.now()),
				head.stream().code(),
				head.epoch(),
				cursor.sequence()) == 1;
		if (recovered) {
			jdbcTemplate.update("DELETE FROM bridge_reorg_pending");
		}
		return recovered;
	}

	public record Cursor(UUID epoch, long sequence) {
	}
}
