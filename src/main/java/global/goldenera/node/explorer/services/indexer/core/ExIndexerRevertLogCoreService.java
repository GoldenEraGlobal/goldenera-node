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

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ExIndexerRevertLogCoreService {

	ObjectMapper objectMapper;
	JdbcTemplate jdbcTemplate;

	public void insertLogBatch(List<Object[]> batch) {
		if (batch.isEmpty())
			return;
		String sql = "INSERT INTO explorer_revert_log (block_height, block_hash, entity_type, operation_type, ref_key_1, ref_key_2, old_value) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)";
		jdbcTemplate.batchUpdate(sql, batch);
	}

	// Helper
	public void addLogToBatch(List<Object[]> batch, Block block, EntityType type, OperationType op,
			byte[] k1, byte[] k2, Object dto) {
		batch.add(new Object[] {
				block.getHeight(),
				block.getHash().toArray(),
				type.getCode(),
				op.getCode(),
				k1,
				k2,
				toJson(dto)
		});
	}

	@SneakyThrows
	private String toJson(Object dto) {
		if (dto == null)
			return null;
		return objectMapper.writeValueAsString(dto);
	}
}
