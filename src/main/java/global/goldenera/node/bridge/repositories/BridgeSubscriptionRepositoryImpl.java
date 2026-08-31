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

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.shared.enums.WebhookType;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BridgeSubscriptionRepositoryImpl implements BridgeSubscriptionRepositoryCustom {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void lockApiKey(long apiKeyId) {
		// Serialize subscribe and unsubscribe before either reads destination state.
		lockDestination(apiKeyId, "bridge-subscriptions");
	}

	@Override
	public long disableSubscriptions(long apiKeyId, Network network, List<UUID> subscriptionIds) {
		requireTransaction();
		if (subscriptionIds != null && subscriptionIds.isEmpty()) {
			return 0L;
		}
		String idFilter = subscriptionIds == null ? "" : " AND subscription.id = ANY (?)";
		List<UUID> destinations = jdbcTemplate.query(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					UPDATE bridge_subscription subscription
					SET enabled = FALSE
					FROM webhook destination
					WHERE subscription.destination_id = destination.id
					  AND destination.created_by_api_key_id = ?
					  AND destination.type = ?
					  AND subscription.network = ?
					  AND subscription.enabled = TRUE
					""" + idFilter + " RETURNING subscription.destination_id");
			statement.setLong(1, apiKeyId);
			statement.setInt(2, WebhookType.BRIDGE.getCode());
			statement.setInt(3, network.getCode());
			if (subscriptionIds != null) {
				statement.setArray(4, connection.createArrayOf("uuid", subscriptionIds.toArray()));
			}
			return statement;
		}, (row, index) -> row.getObject("destination_id", UUID.class));
		if (!destinations.isEmpty()) {
			jdbcTemplate.update(connection -> {
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE webhook destination
						SET enabled = FALSE, version = version + 1
						WHERE destination.id = ANY (?)
						  AND destination.created_by_api_key_id = ?
						  AND destination.type = ?
						  AND destination.enabled = TRUE
						  AND NOT EXISTS (
						      SELECT 1 FROM bridge_subscription subscription
						      WHERE subscription.destination_id = destination.id
						        AND subscription.enabled = TRUE)
						""");
				statement.setArray(1, connection.createArrayOf("uuid", destinations.stream().distinct().toArray()));
				statement.setLong(2, apiKeyId);
				statement.setInt(3, WebhookType.BRIDGE.getCode());
				return statement;
			});
		}
		return destinations.size();
	}

	@Override
	public void lockDestination(long apiKeyId, String destinationKey) {
		requireTransaction();
		jdbcTemplate.query(
				"SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
				resultSet -> {
					resultSet.next();
					return null;
				},
				destinationKey,
				apiKeyId);
	}

	@Override
	public UUID upsertEnabled(
			UUID candidateId,
			UUID destinationId,
			Network network,
			Address address,
			UUID canonicalEpoch,
			long canonicalSequence,
			UUID mempoolEpoch,
			long mempoolSequence,
			long canonicalHeight,
			Instant now) {
		requireTransaction();
		return jdbcTemplate.queryForObject("""
				INSERT INTO bridge_subscription (
				    id, destination_id, network, address, enabled, created_at,
				    active_from_canonical_epoch, active_from_canonical_sequence,
				    active_from_mempool_epoch, active_from_mempool_sequence,
				    active_after_canonical_height)
				VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (destination_id, network, address) DO UPDATE SET
				    enabled = TRUE,
				    active_from_canonical_epoch = CASE
				        WHEN bridge_subscription.enabled THEN bridge_subscription.active_from_canonical_epoch
				        ELSE EXCLUDED.active_from_canonical_epoch END,
				    active_from_canonical_sequence = CASE
				        WHEN bridge_subscription.enabled THEN bridge_subscription.active_from_canonical_sequence
				        ELSE EXCLUDED.active_from_canonical_sequence END,
				    active_from_mempool_epoch = CASE
				        WHEN bridge_subscription.enabled THEN bridge_subscription.active_from_mempool_epoch
				        ELSE EXCLUDED.active_from_mempool_epoch END,
				    active_from_mempool_sequence = CASE
				        WHEN bridge_subscription.enabled THEN bridge_subscription.active_from_mempool_sequence
				        ELSE EXCLUDED.active_from_mempool_sequence END,
				    active_after_canonical_height = CASE
				        WHEN bridge_subscription.enabled THEN bridge_subscription.active_after_canonical_height
				        ELSE EXCLUDED.active_after_canonical_height END
				RETURNING id
				""", UUID.class,
				candidateId,
				destinationId,
				network.getCode(),
				address.toArray(),
				Timestamp.from(now),
				canonicalEpoch,
				canonicalSequence,
				mempoolEpoch,
				mempoolSequence,
				canonicalHeight);
	}

	private void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Bridge subscription writes require an active transaction");
		}
	}
}
