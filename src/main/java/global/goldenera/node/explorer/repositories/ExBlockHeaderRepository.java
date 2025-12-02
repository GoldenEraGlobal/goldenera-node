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
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.node.explorer.entities.ExBlockHeader;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface ExBlockHeaderRepository
        extends BaseJpaRepository<ExBlockHeader, ExBlockHeader.ExBlockHeaderPK>,
        ListPagingAndSortingRepository<ExBlockHeader, ExBlockHeader.ExBlockHeaderPK>,
        JpaSpecificationExecutor<ExBlockHeader> {

    @Query(value = "SELECT cumulative_difficulty FROM explorer_block_header WHERE hash = :hash", nativeQuery = true)
    Optional<Long> findCumulativeDifficultyByHash(@Param("hash") byte[] hash);

    @Query(value = "SELECT * FROM explorer_block_header ORDER BY height DESC LIMIT 1", nativeQuery = true)
    Optional<ExBlockHeader> findLatest();

    @Query(value = "SELECT * FROM explorer_block_header WHERE height = :height LIMIT 1", nativeQuery = true)
    Optional<ExBlockHeader> findByHeight(@Param("height") long height);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM explorer_block_header WHERE hash = :hash)", nativeQuery = true)
    boolean existsByHash(@Param("hash") byte[] hash);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM explorer_block_header WHERE height = :height)", nativeQuery = true)
    boolean existsByHeight(@Param("height") long height);

    @Query(value = "SELECT COUNT(*) FROM explorer_block_header WHERE coinbase = :coinbase", nativeQuery = true)
    long countByCoinbase(@Param("coinbase") byte[] coinbase);

    @Query(value = """
                SELECT *
                FROM explorer_block_header
                WHERE height BETWEEN :fromHeight AND :toHeight
                ORDER BY height ASC
            """, nativeQuery = true)
    List<ExBlockHeader> findByHeightRange(@Param("fromHeight") long fromHeight, @Param("toHeight") long toHeight);

    @Query(value = """
            SELECT * FROM explorer_block_header
            WHERE height > (SELECT height FROM explorer_block_header WHERE hash = :commonAncestorHash)
            AND height <= (SELECT height FROM explorer_block_header WHERE hash = :currentBestBlockHash)
            ORDER BY height DESC
            """, nativeQuery = true)
    List<ExBlockHeader> findChainFrom(
            @Param("commonAncestorHash") byte[] commonAncestorHash,
            @Param("currentBestBlockHash") byte[] currentBestBlockHash);
}