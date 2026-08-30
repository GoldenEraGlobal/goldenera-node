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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.shared.entities.Webhook;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface BridgeSubscriptionRepository extends BaseJpaRepository<BridgeSubscription, UUID>,
		ListCrudRepository<BridgeSubscription, UUID>, BridgeSubscriptionRepositoryCustom {

    @Query("""
            SELECT subscription
            FROM BridgeSubscription subscription
            JOIN FETCH subscription.destination destination
            JOIN FETCH destination.createdByApiKey apiKey
            WHERE subscription.enabled = true
              AND subscription.network = :network
			  AND subscription.address IN :addresses
			  AND (:epoch IS NULL OR
			       (:stream = 0 AND subscription.activeFromCanonicalEpoch = :epoch
			                    AND subscription.activeFromCanonicalSequence < :sequence)
			       OR (:stream = 1 AND subscription.activeFromMempoolEpoch = :epoch
			                    AND subscription.activeFromMempoolSequence < :sequence))
			  AND (:canonicalHeight IS NULL OR subscription.activeAfterCanonicalHeight < :canonicalHeight)
              AND destination.enabled = true
              AND apiKey.enabled = true
            """)
	List<BridgeSubscription> findEnabledByNetworkAndAddressIn(
			@Param("network") Network network,
			@Param("addresses") Collection<Address> addresses,
			@Param("stream") int stream,
			@Param("epoch") UUID epoch,
			@Param("sequence") long sequence,
			@Param("canonicalHeight") Long canonicalHeight);

	@Query("""
			SELECT CASE WHEN COUNT(subscription) > 0 THEN true ELSE false END
			FROM BridgeSubscription subscription
			JOIN subscription.destination destination
			JOIN destination.createdByApiKey apiKey
			WHERE subscription.enabled = true
			  AND subscription.network = :network
			  AND destination.enabled = true
			  AND apiKey.enabled = true
			  AND ((:stream = 0 AND subscription.activeFromCanonicalEpoch = :epoch
			                    AND subscription.activeFromCanonicalSequence < :sequence)
			       OR (:stream = 1 AND subscription.activeFromMempoolEpoch = :epoch
			                    AND subscription.activeFromMempoolSequence < :sequence))
			  AND (:canonicalHeight IS NULL OR subscription.activeAfterCanonicalHeight < :canonicalHeight)
			""")
	boolean existsEnabledForSource(
			@Param("network") Network network,
			@Param("stream") int stream,
			@Param("epoch") UUID epoch,
			@Param("sequence") long sequence,
			@Param("canonicalHeight") Long canonicalHeight);

    @Query("""
            SELECT DISTINCT subscription.destination
            FROM BridgeSubscription subscription
            WHERE subscription.destination.createdByApiKey.id = :apiKeyId
              AND subscription.destination.bridgeDestinationKey = :destinationKey
            """)
    Optional<Webhook> findReusableDestination(
            @Param("apiKeyId") long apiKeyId,
            @Param("destinationKey") String destinationKey);

    Optional<BridgeSubscription> findByDestinationIdAndNetworkAndAddress(
            UUID destinationId,
            Network network,
            Address address);

    @Query("SELECT COUNT(subscription) FROM BridgeSubscription subscription WHERE subscription.destination.id = :destinationId AND subscription.enabled = true")
    long countEnabledByDestinationId(@Param("destinationId") UUID destinationId);
}
