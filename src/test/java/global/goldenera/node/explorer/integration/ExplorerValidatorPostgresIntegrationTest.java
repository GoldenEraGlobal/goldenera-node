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
package global.goldenera.node.explorer.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.BipStateVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.cryptoj.enums.state.BipType;
import global.goldenera.cryptoj.enums.state.AccountBalanceStateVersion;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerRevertService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerConsensusCoreService;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.BalanceRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.BipRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.TokenRevertDto;
import global.goldenera.node.explorer.services.indexer.core.data.ExIndexerRevertDtos.ValidatorRevertDto;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class ExplorerValidatorPostgresIntegrationTest {

	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
	private static final Address VALIDATOR =
			Address.fromHexString("0x1111111111111111111111111111111111111111");
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
	private static final Hash ORIGIN_TX = hash(1);
	private static final Address TOKEN =
			Address.fromHexString("0x2222222222222222222222222222222222222222");
	private static final Hash BIP_HASH = hash(30);

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:17-alpine")
					.withDatabaseName("goldenera")
					.withUsername("goldenera")
					.withPassword("goldenera")
					.withCommand("postgres", "-c", "timezone=Europe/Prague");

	private static JdbcTemplate jdbcTemplate;
	private static ExIndexerConsensusCoreService consensusService;
	private static ExIndexerRevertService revertService;
	private static final ObjectMapper JSON = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@BeforeAll
	static void migrateDatabase() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(MASTER_CHANGELOG, resources, database)) {
				liquibase.update();
			}
		}

		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
		consensusService = new ExIndexerConsensusCoreService(jdbcTemplate);
		revertService = new ExIndexerRevertService(jdbcTemplate);
	}

	@Test
	void v1V2UpsertsAndRepeatedBranchRevertsPreserveExactLegacySnapshot() throws Exception {
		ValidatorState v1 = legacyValidator();
		consensusService.bulkUpsertValidators(Map.of(VALIDATOR, v1));
		assertLegacyRow();

		ValidatorState limitedBranch = explicitValidator(MiningLimitMode.LIMITED, 2_500, hash(2), 10);
		applyBranchAndRecordLegacySnapshot(limitedBranch, hash(10), 10, v1);
		assertExplicitRow(MiningLimitMode.LIMITED, 2_500, hash(2), 10);
		revertValidatorUpdate(hash(10));
		assertLegacyRow();

		ValidatorState unlimitedBranch = explicitValidator(MiningLimitMode.UNLIMITED, 0, hash(3), 10);
		applyBranchAndRecordLegacySnapshot(unlimitedBranch, hash(11), 10, v1);
		assertExplicitRow(MiningLimitMode.UNLIMITED, 0, hash(3), 10);
		revertValidatorUpdate(hash(11));
		assertLegacyRow();

		consensusService.bulkUpsertValidators(Map.of(VALIDATOR, limitedBranch));
		assertExplicitRow(MiningLimitMode.LIMITED, 2_500, hash(2), 10);
	}

	@Test
	void balanceRevertRestoresNonzeroPendingMiningRewardCancellation() throws Exception {
		Hash blockHash = hash(20);
		jdbcTemplate.update("""
				INSERT INTO explorer_account_balance
				(address, token_address, balance, locked_mining_reward, pending_mining_reward_cancellation,
				 created_at_block_height, created_at_timestamp, updated_at_block_height,
				 updated_at_timestamp, account_balance_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				VALIDATOR.toArray(), Address.NATIVE_TOKEN.toArray(), 25, 10, 5,
				1, Timestamp.from(CREATED_AT), 20, Timestamp.from(CREATED_AT.plusSeconds(20)),
				AccountBalanceStateVersion.V2.getCode());
		BalanceRevertDto previous = BalanceRevertDto.from(
				Wei.valueOf(70), Wei.valueOf(40), Wei.valueOf(30), 11,
				CREATED_AT.plusSeconds(11), AccountBalanceStateVersion.V2);
		jdbcTemplate.update("""
				INSERT INTO explorer_revert_log
				(block_height, block_hash, entity_type, operation_type, ref_key_1, ref_key_2, old_value)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
				""",
				20,
				blockHash.toArray(),
				EntityType.ACCOUNT_BALANCE.getCode(),
				OperationType.UPDATE.getCode(),
				VALIDATOR.toArray(),
				Address.NATIVE_TOKEN.toArray(),
				JSON.writeValueAsString(previous));

		ReflectionTestUtils.invokeMethod(revertService, "revertBalances", blockHash.toArray(), 20L);

		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT balance, locked_mining_reward, pending_mining_reward_cancellation,
				       updated_at_block_height, account_balance_version
				FROM explorer_account_balance
				WHERE address = ? AND token_address = ?
				""", VALIDATOR.toArray(), Address.NATIVE_TOKEN.toArray());
		assertThat((Number) row.get("balance")).hasToString("70");
		assertThat((Number) row.get("locked_mining_reward")).hasToString("40");
		assertThat((Number) row.get("pending_mining_reward_cancellation")).hasToString("30");
		assertThat(((Number) row.get("updated_at_block_height")).longValue()).isEqualTo(11);
		assertThat(((Number) row.get("account_balance_version")).intValue())
				.isEqualTo(AccountBalanceStateVersion.V2.getCode());
	}

	@Test
	void tokenRevertRestoresEveryMutableFieldIncludingNullMetadata() throws Exception {
		Hash blockHash = hash(21);
		Hash branchUpdateTx = hash(22);
		Hash previousUpdateTx = hash(23);
		jdbcTemplate.update("""
				INSERT INTO explorer_token
				(address, name, smallest_unit_name, number_of_decimals, website_url, logo_url,
				 max_supply, total_supply, origin_tx_hash, updated_by_tx_hash,
				 created_at_block_height, created_at_timestamp, updated_at_block_height,
				 updated_at_timestamp, user_burnable, token_state_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				TOKEN.toArray(), "Branch Name", "BRANCH", 8, "https://branch.example", "branch-logo",
				1_000, 90, ORIGIN_TX.toArray(), branchUpdateTx.toArray(),
				1, Timestamp.from(CREATED_AT), 21, Timestamp.from(CREATED_AT.plusSeconds(21)),
				true, TokenStateVersion.V1.getCode());
		TokenRevertDto previous = TokenRevertDto.from(
				Wei.valueOf(70), 11, CREATED_AT.plusSeconds(11), "Canonical Name", "CAN",
				null, null, previousUpdateTx, TokenStateVersion.V1);
		insertRevertLog(blockHash, 21, EntityType.TOKEN, TOKEN.toArray(), null, previous);

		ReflectionTestUtils.invokeMethod(revertService, "revertTokens", blockHash.toArray(), 21L);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT * FROM explorer_token WHERE address = ?", TOKEN.toArray());
		assertThat(row.get("name")).isEqualTo("Canonical Name");
		assertThat(row.get("smallest_unit_name")).isEqualTo("CAN");
		assertThat(row.get("logo_url")).isNull();
		assertThat(row.get("website_url")).isNull();
		assertThat(row.get("updated_by_tx_hash")).isEqualTo(previousUpdateTx.toArray());
		assertThat((Number) row.get("total_supply")).hasToString("70");
		assertThat(((Number) row.get("updated_at_block_height")).longValue()).isEqualTo(11);
		assertTimestamp("explorer_token", "address", TOKEN.toArray(), "updated_at_timestamp",
				CREATED_AT.plusSeconds(11));
	}

	@Test
	void bipRevertRestoresExecutionStateAndTimestampInNonUtcDatabase() throws Exception {
		Hash blockHash = hash(31);
		Hash branchUpdateTx = hash(32);
		Hash previousUpdateTx = hash(33);
		jdbcTemplate.update("""
				INSERT INTO explorer_bip_state
				(bip_hash, bip_state_version, status, is_action_executed, type,
				 number_of_required_votes, approvers, disapprovers, expiration_timestamp,
				 executed_at_timestamp, origin_tx_hash, updated_by_tx_hash,
				 created_at_block_height, created_at_timestamp, updated_at_block_height,
				 updated_at_timestamp)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				BIP_HASH.toArray(), BipStateVersion.V1.getCode(), BipStatus.APPROVED.getCode(), true,
				BipType.TOKEN_BURN.getCode(), 1, Bytes.EMPTY.toArray(), Bytes.EMPTY.toArray(),
				Timestamp.from(CREATED_AT.plusSeconds(3_600)), Timestamp.from(CREATED_AT.plusSeconds(20)),
				ORIGIN_TX.toArray(), branchUpdateTx.toArray(), 1, Timestamp.from(CREATED_AT),
				31, Timestamp.from(CREATED_AT.plusSeconds(31)));
		BipRevertDto previous = new BipRevertDto(
				BipStatus.PENDING.getCode(), 11, CREATED_AT.plusSeconds(11),
				previousUpdateTx.toHexString(), Bytes.EMPTY.toHexString(), Bytes.EMPTY.toHexString(),
				false, null, BipStateVersion.V1.getCode());
		insertRevertLog(blockHash, 31, EntityType.BIP, BIP_HASH.toArray(), null, previous);

		ReflectionTestUtils.invokeMethod(revertService, "revertBips", blockHash.toArray(), 31L);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT * FROM explorer_bip_state WHERE bip_hash = ?", BIP_HASH.toArray());
		assertThat(((Number) row.get("status")).intValue()).isEqualTo(BipStatus.PENDING.getCode());
		assertThat(row.get("is_action_executed")).isEqualTo(false);
		assertThat(row.get("executed_at_timestamp")).isNull();
		assertThat(row.get("updated_by_tx_hash")).isEqualTo(previousUpdateTx.toArray());
		assertThat(((Number) row.get("updated_at_block_height")).longValue()).isEqualTo(11);
		assertTimestamp("explorer_bip_state", "bip_hash", BIP_HASH.toArray(), "updated_at_timestamp",
				CREATED_AT.plusSeconds(11));
	}

	private void applyBranchAndRecordLegacySnapshot(
			ValidatorState branchState, Hash blockHash, long height, ValidatorState previousState) throws Exception {
		consensusService.bulkUpsertValidators(Map.of(VALIDATOR, branchState));
		String oldValue = JSON.writeValueAsString(ValidatorRevertDto.from(previousState));
		jdbcTemplate.update("""
				INSERT INTO explorer_revert_log
				(block_height, block_hash, entity_type, operation_type, ref_key_1, old_value)
				VALUES (?, ?, ?, ?, ?, ?::jsonb)
				""",
				height,
				blockHash.toArray(),
				EntityType.VALIDATOR.getCode(),
				OperationType.UPDATE.getCode(),
				VALIDATOR.toArray(),
				oldValue);
	}

	private void revertValidatorUpdate(Hash blockHash) {
		ReflectionTestUtils.invokeMethod(revertService, "revertValidators", blockHash.toArray(), 10L);
	}

	private void insertRevertLog(
			Hash blockHash, long blockHeight, EntityType entityType, byte[] refKey1, byte[] refKey2, Object previous)
			throws Exception {
		jdbcTemplate.update("""
				INSERT INTO explorer_revert_log
				(block_height, block_hash, entity_type, operation_type, ref_key_1, ref_key_2, old_value)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
				""",
				blockHeight,
				blockHash.toArray(),
				entityType.getCode(),
				OperationType.UPDATE.getCode(),
				refKey1,
				refKey2,
				JSON.writeValueAsString(previous));
	}

	private void assertTimestamp(
			String table, String keyColumn, byte[] key, String timestampColumn, Instant expected) {
		BigDecimal epoch = jdbcTemplate.queryForObject(
				"SELECT EXTRACT(EPOCH FROM " + timestampColumn + ") FROM " + table + " WHERE " + keyColumn + " = ?",
				BigDecimal.class,
				key);
		assertThat(epoch).isEqualByComparingTo(BigDecimal.valueOf(expected.getEpochSecond()));
	}

	private void assertLegacyRow() {
		Map<String, Object> row = validatorRow();
		assertThat(((Number) row.get("validator_version")).intValue())
				.isEqualTo(ValidatorStateVersion.V1.getCode());
		assertThat(row.get("origin_tx_hash")).isEqualTo(ORIGIN_TX.toArray());
		assertThat(((Number) row.get("created_at_block_height")).longValue()).isEqualTo(1);
		assertThat(row.get("mining_limit_mode")).isNull();
		assertThat(row.get("mining_policy_source")).isNull();
		assertThat(row.get("max_mining_share_bps")).isNull();
		assertThat(row.get("policy_updated_by_tx_hash")).isNull();
		assertThat(row.get("policy_updated_at_block_height")).isNull();
		assertThat(row.get("policy_updated_at_timestamp")).isNull();
	}

	private void assertExplicitRow(MiningLimitMode mode, long bps, Hash updateTx, long height) {
		Map<String, Object> row = validatorRow();
		assertThat(((Number) row.get("validator_version")).intValue())
				.isEqualTo(ValidatorStateVersion.V2.getCode());
		assertThat(row.get("mining_limit_mode")).isEqualTo(mode.name());
		assertThat(row.get("mining_policy_source")).isEqualTo("EXPLICIT");
		assertThat(((Number) row.get("max_mining_share_bps")).longValue()).isEqualTo(bps);
		assertThat(row.get("policy_updated_by_tx_hash")).isEqualTo(updateTx.toArray());
		assertThat(((Number) row.get("policy_updated_at_block_height")).longValue()).isEqualTo(height);
		assertThat(row.get("policy_updated_at_timestamp")).isNotNull();
	}

	private Map<String, Object> validatorRow() {
		return jdbcTemplate.queryForMap("SELECT * FROM explorer_validator WHERE address = ?", VALIDATOR.toArray());
	}

	private static ValidatorState legacyValidator() {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V1)
				.originTxHash(ORIGIN_TX)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(CREATED_AT)
				.build();
	}

	private static ValidatorState explicitValidator(
			MiningLimitMode mode, long bps, Hash updateTx, long updateHeight) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(ORIGIN_TX)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(CREATED_AT)
				.miningLimitMode(mode)
				.maxMiningShareBps(bps)
				.policyUpdatedByTxHash(updateTx)
				.policyUpdatedAtBlockHeight(updateHeight)
				.policyUpdatedAtTimestamp(CREATED_AT.plusSeconds(updateHeight))
				.build();
	}

	private static Hash hash(int value) {
		return Hash.fromHexString("0x" + "%064x".formatted(value));
	}
}
