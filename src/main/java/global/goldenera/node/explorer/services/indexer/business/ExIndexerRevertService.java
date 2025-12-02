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
package global.goldenera.node.explorer.services.indexer.business;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExIndexerRevertService {

	JdbcTemplate jdbcTemplate;

	@Transactional(propagation = Propagation.MANDATORY)
	public void revertBlock(Hash blockHash, long blockHeight) {
		log.info("Reverting block {} at height {}", blockHash, blockHeight);

		// Convert Hash to byte[] for JDBC
		byte[] blockHashBytes = blockHash.toArray();

		// 1. Revert WorldState (Logika: Read logs -> Apply old values)
		revertBalances(blockHashBytes);
		revertNonces(blockHashBytes);
		revertTokens(blockHashBytes);
		revertAliases(blockHashBytes);
		revertAuthorities(blockHashBytes);
		revertBips(blockHashBytes);
		revertNetworkParams(blockHashBytes);

		// 2. Delete Append-Only Data (TXs, Transfers)
		jdbcTemplate.update("DELETE FROM explorer_transfer WHERE block_hash = ?", blockHashBytes);
		jdbcTemplate.update("DELETE FROM explorer_tx WHERE block_hash = ?", blockHashBytes);

		// 3. Delete Block Header
		jdbcTemplate.update("DELETE FROM explorer_block_header WHERE hash = ?", blockHashBytes);

		// 4. Cleanup Revert Logs
		jdbcTemplate.update("DELETE FROM explorer_revert_log WHERE block_hash = ?", blockHashBytes);

		// 5. Update Explorer Status (Head pointer)
		updateStatusAfterRevert(blockHeight - 1);

		log.info("Block {} reverted successfully.", blockHash);
	}

	// =================================================================================
	// SPECIFIC ENTITY REVERTS
	// =================================================================================

	private void revertBalances(byte[] blockHash) {
		String sqlDelete = """
				    DELETE FROM explorer_account_balance b
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND b.address = l.ref_key_1
				      AND b.token_address = l.ref_key_2
				""";
		// EntityType: BALANCE, Op: INSERT -> DELETE
		jdbcTemplate.update(sqlDelete, blockHash, EntityType.ACCOUNT_BALANCE.getCode(), OperationType.INSERT.getCode());

		String sqlUpdate = """
				    UPDATE explorer_account_balance b
				    SET
				        balance = (l.old_value->>'b')::numeric,
				        updated_at_block_height = (l.old_value->>'uh')::bigint,
				        updated_at_timestamp = (l.old_value->>'ut')::timestamp,
						account_balance_version = (l.old_value->>'ver')::integer
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND b.address = l.ref_key_1
				      AND b.token_address = l.ref_key_2
				""";
		// EntityType: BALANCE, Op: UPDATE -> RESTORE OLD VALUE
		jdbcTemplate.update(sqlUpdate, blockHash, EntityType.ACCOUNT_BALANCE.getCode(), OperationType.UPDATE.getCode());
	}

	private void revertNonces(byte[] blockHash) {
		// 1. Revert INSERT -> DELETE
		jdbcTemplate.update("""
				    DELETE FROM explorer_account_nonce n
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND n.address = l.ref_key_1
				""", blockHash, EntityType.ACCOUNT_NONCE.getCode(), OperationType.INSERT.getCode());

		// 2. Revert UPDATE -> RESTORE
		jdbcTemplate.update("""
				    UPDATE explorer_account_nonce n
				    SET
				        nonce = (l.old_value->>'n')::bigint,
				        updated_at_block_height = (l.old_value->>'uh')::bigint,
				        updated_at_timestamp = (l.old_value->>'ut')::timestamp,
						account_nonce_version = (l.old_value->>'ver')::integer
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND n.address = l.ref_key_1
				""", blockHash, EntityType.ACCOUNT_NONCE.getCode(), OperationType.UPDATE.getCode());
	}

	private void revertTokens(byte[] blockHash) {
		// 1. Revert INSERT (Create Token) -> DELETE
		jdbcTemplate.update("""
				    DELETE FROM explorer_token t
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND t.address = l.ref_key_1
				""", blockHash, EntityType.TOKEN.getCode(), OperationType.INSERT.getCode());

		// 2. Revert UPDATE -> RESTORE (TotalSupply, URL...)
		jdbcTemplate.update(
				"""
						    UPDATE explorer_token t
						    SET
						        total_supply = (l.old_value->>'ts')::numeric,
						        updated_at_block_height = (l.old_value->>'uh')::bigint,
						        updated_at_timestamp = (l.old_value->>'ut')::timestamp,
						        name = COALESCE(l.old_value->>'name', t.name),
						        logo_url = COALESCE(l.old_value->>'logo', t.logo_url),
								website_url = COALESCE(l.old_value->>'web', t.website_url),
								token_state_version = (l.old_value->>'ver')::integer
						    FROM explorer_revert_log l
						    WHERE l.block_hash = ?
						      AND l.entity_type = ?
						      AND l.operation_type = ?
						      AND t.address = l.ref_key_1
						""",
				blockHash, EntityType.TOKEN.getCode(), OperationType.UPDATE.getCode());
	}

	private void revertAliases(byte[] blockHash) {
		// 1. Revert INSERT (New Alias) -> DELETE
		jdbcTemplate.update("""
				    DELETE FROM explorer_address_alias a
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND a.alias = convert_from(l.ref_key_1, 'UTF8')
				""", blockHash, EntityType.ADDRESS_ALIAS.getCode(), OperationType.INSERT.getCode());

		// 2. Revert DELETE (Removed Alias) -> INSERT (Restore it!)
		String sqlRestoreDeleted = """
				    INSERT INTO explorer_address_alias
				    (alias, address, origin_tx_hash, created_at_block_height, created_at_timestamp, address_alias_version)
				    SELECT
				        convert_from(l.ref_key_1, 'UTF8'),
				        decode(l.old_value->>'addr', 'hex'),
				        decode(l.old_value->>'tx', 'hex'),
				        (l.old_value->>'ch')::bigint,
				        (l.old_value->>'ct')::timestamp,
				        (l.old_value->>'ver')::integer
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				""";
		jdbcTemplate.update(sqlRestoreDeleted, blockHash, EntityType.ADDRESS_ALIAS.getCode(),
				OperationType.DELETE.getCode());
	}

	private void revertAuthorities(byte[] blockHash) {
		// 1. Revert Add (INSERT) -> DELETE
		jdbcTemplate.update("""
				    DELETE FROM explorer_authority a
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND a.address = l.ref_key_1
				""", blockHash, EntityType.AUTHORITY.getCode(), OperationType.INSERT.getCode());

		// 2. Revert Remove (DELETE) -> INSERT
		String sqlRestore = """
				    INSERT INTO explorer_authority
				    (address, origin_tx_hash, created_at_block_height, created_at_timestamp, authority_version)
				    SELECT
				        l.ref_key_1, -- Address PK
				        decode(l.old_value->>'tx', 'hex'),
				        (l.old_value->>'ch')::bigint,
				        (l.old_value->>'ct')::timestamp,
				        (l.old_value->>'ver')::integer
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				""";
		jdbcTemplate.update(sqlRestore, blockHash, EntityType.AUTHORITY.getCode(), OperationType.DELETE.getCode());
	}

	private void revertBips(byte[] blockHash) {
		// 1. Revert INSERT -> DELETE
		jdbcTemplate.update("""
				    DELETE FROM explorer_bip_state b
				    USING explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND b.bip_hash = l.ref_key_1
				""", blockHash, EntityType.BIP.getCode(), OperationType.INSERT.getCode());

		// 2. Revert UPDATE -> RESTORE
		jdbcTemplate.update("""
				    UPDATE explorer_bip_state b
				    SET
				        status = (l.old_value->>'s')::integer,
				        approvers = decode(l.old_value->>'appr', 'hex'),
				        disapprovers = decode(l.old_value->>'disappr', 'hex'),
				        updated_at_block_height = (l.old_value->>'uh')::bigint,
				        updated_at_timestamp = (l.old_value->>'ut')::timestamp,
				        updated_by_tx_hash = decode(l.old_value->>'utx', 'hex'),
						bip_state_version = (l.old_value->>'ver')::integer
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND b.bip_hash = l.ref_key_1
				""", blockHash, EntityType.BIP.getCode(), OperationType.UPDATE.getCode());
	}

	private void revertNetworkParams(byte[] blockHash) {
		jdbcTemplate.update("""
				    UPDATE explorer_network_params p
				    SET
				        network_params_version = (l.old_value->>'ver')::integer,

				        block_reward = (l.old_value->>'br')::numeric,
				        block_reward_pool_address = decode(l.old_value->>'pool', 'hex'),

				        target_mining_time_ms = (l.old_value->>'tgt')::bigint,
				        asert_half_life_blocks = (l.old_value->>'half')::bigint,
				        asert_anchor_height = (l.old_value->>'anch')::bigint,
				        min_difficulty = (l.old_value->>'diff')::numeric,

				        min_tx_base_fee = (l.old_value->>'base')::numeric,
				        min_tx_byte_fee = (l.old_value->>'byte')::numeric,

				        current_authority_count = (l.old_value->>'auth_cnt')::bigint,
				        updated_by_tx_hash = decode(l.old_value->>'utx', 'hex'),

				        updated_at_block_height = (l.old_value->>'uh')::bigint,
				        updated_at_timestamp = (l.old_value->>'ut')::timestamp
				    FROM explorer_revert_log l
				    WHERE l.block_hash = ?
				      AND l.entity_type = ?
				      AND l.operation_type = ?
				      AND p.id = 1
				""", blockHash, EntityType.NETWORK_PARAMS.getCode(), OperationType.UPDATE.getCode());
	}

	private void updateStatusAfterRevert(long newHeight) {
		String sqlGetHash = "SELECT hash FROM explorer_block_header WHERE height = ? LIMIT 1";

		try {
			byte[] hash = jdbcTemplate.queryForObject(sqlGetHash, byte[].class, newHeight);

			String sqlUpdate = "UPDATE explorer_status SET synced_block_height = ?, synced_block_hash = ?, last_updated_at = ? WHERE id = 1";
			jdbcTemplate.update(sqlUpdate, newHeight, hash, java.sql.Timestamp.from(Instant.now()));
		} catch (Exception e) {
			log.error("Failed to update status after revert. Critical!", e);
			throw new GEFailedException("Database inconsistency after revert", e);
		}
	}
}