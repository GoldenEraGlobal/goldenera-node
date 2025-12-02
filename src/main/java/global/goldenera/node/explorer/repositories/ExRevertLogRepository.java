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
package global.goldenera.node.explorer.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.node.explorer.entities.ExRevertLog;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface ExRevertLogRepository
		extends BaseJpaRepository<ExRevertLog, Long>,
		ListPagingAndSortingRepository<ExRevertLog, Long>,
		JpaSpecificationExecutor<ExRevertLog> {

	@Query(value = """
			SELECT * FROM explorer_revert_log
			WHERE block_hash = :blockHash
			ORDER BY id DESC
			""", nativeQuery = true)
	List<ExRevertLog> findAllByBlockHash(@Param("blockHash") byte[] blockHash);

	@Modifying
	@Query(value = "DELETE FROM explorer_revert_log WHERE block_hash = :blockHash", nativeQuery = true)
	void deleteByBlockHash(@Param("blockHash") byte[] blockHash);
}