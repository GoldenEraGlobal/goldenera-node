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

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;

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
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerRevertService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerConsensusCoreService;
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

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:17-alpine")
					.withDatabaseName("goldenera")
					.withUsername("goldenera")
					.withPassword("goldenera");

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
		ReflectionTestUtils.invokeMethod(revertService, "revertValidators", blockHash.toArray());
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
