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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerStatusCoreService {

	JdbcTemplate jdbcTemplate;

	private static final RowMapper<ExStatus> STATUS_ROW_MAPPER = (rs, rowNum) -> {
		return new ExStatus(
				1,
				rs.getLong("synced_block_height"),
				Hash.wrap(rs.getBytes("synced_block_hash")),
				rs.getTimestamp("last_updated_at").toInstant(),
				rs.getString("app_version"));
	};

	public ExStatus getStatusOrThrow() {
		return getStatus().orElseThrow(() -> new GENotFoundException("Explorer: Status not found"));
	}

	public Optional<ExStatus> getStatus() {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
					"SELECT synced_block_height, synced_block_hash, last_updated_at, app_version FROM explorer_status WHERE id = 1",
					STATUS_ROW_MAPPER));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	public void updateStatus(BlockHeader blockHeader) {
		String sql = """
				    INSERT INTO explorer_status (id, synced_block_height, synced_block_hash, last_updated_at)
				    VALUES (1, ?, ?, ?)
				    ON CONFLICT (id) DO UPDATE SET
				        synced_block_height = EXCLUDED.synced_block_height,
				        synced_block_hash = EXCLUDED.synced_block_hash,
				        last_updated_at = EXCLUDED.last_updated_at
				""";
		jdbcTemplate.update(sql, blockHeader.getHeight(), blockHeader.getHash().toArray(),
				Timestamp.from(Instant.now()));
	}
}
