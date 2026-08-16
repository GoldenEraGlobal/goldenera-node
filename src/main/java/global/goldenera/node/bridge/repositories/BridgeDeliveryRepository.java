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
package global.goldenera.node.bridge.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.node.bridge.entities.BridgeDelivery;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface BridgeDeliveryRepository extends BaseJpaRepository<BridgeDelivery, Long>,
        ListCrudRepository<BridgeDelivery, Long>, BridgeDeliveryRepositoryCustom {

    @Query("""
            SELECT delivery
            FROM BridgeDelivery delivery
            JOIN FETCH delivery.destination destination
            JOIN FETCH destination.createdByApiKey
            WHERE delivery.deliveryId = :deliveryId
            """)
    Optional<BridgeDelivery> findByDeliveryId(@Param("deliveryId") UUID deliveryId);

    boolean existsByDestinationIdAndEventId(UUID destinationId, UUID eventId);

    @Query(value = """
            SELECT delivery.*
            FROM bridge_delivery delivery
            JOIN webhook destination ON destination.id = delivery.destination_id
            JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
            WHERE delivery.body IS NOT NULL
              AND destination.enabled = TRUE
              AND api_key_row.enabled = TRUE
              AND (
                    (delivery.state IN (0, 3) AND delivery.next_attempt_at <= :now)
                    OR (delivery.state = 1 AND delivery.lease_until < :now)
                  )
              AND NOT EXISTS (
                    SELECT 1
                    FROM bridge_delivery older
                    WHERE older.destination_id = delivery.destination_id
                      AND older.id < delivery.id
                      AND older.state NOT IN (2, 4)
                  )
            ORDER BY delivery.id
            FOR UPDATE OF delivery SKIP LOCKED
            """, nativeQuery = true)
    List<BridgeDelivery> findClaimableForUpdate(@Param("now") Instant now, Pageable pageable);
}
