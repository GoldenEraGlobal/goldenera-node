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
package global.goldenera.node.core.storage.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateDiff;
import global.goldenera.node.shared.config.JacksonConfig;

class EntityIndexRepositoryReorgIntegrationTest {

	private static final Address AUTHORITY = Address.fromHexString("0x0000000000000000000000000000000000000042");
	private static final Address VALIDATOR = Address.fromHexString("0x0000000000000000000000000000000000000043");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@TempDir
	Path temporaryDirectory;

	@Test
	void consecutiveCandidateUndoLogsUseBranchDiffsInsteadOfLosingHeadDatabaseState() throws Exception {
		Path databasePath = temporaryDirectory.resolve("blockchain");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(databasePath)).open(databasePath, families);
		try {
			RocksDBRepository rocks = new RocksDBRepository(database, families);
			ObjectMapper mapper = new JacksonConfig().baseObjectMapper(new Jackson2ObjectMapperBuilder());
			EntityIndexRepository indexes = repository(rocks, mapper);
			AuthorityState ancestor = authority(1);
			AuthorityState losingHead = authority(2);
			AuthorityState candidateOne = authority(3);
			AuthorityState candidateTwo = authority(4);
			Block first = block(101);
			Block second = block(102);

			rocks.executeAtomicBatch(batch -> batch.put(
					families.authorities(), AUTHORITY.toArray(), mapper.writeValueAsBytes(losingHead)));
			rocks.executeAtomicBatch(batch -> {
				indexes.saveEntities(batch, first, worldState(ancestor, candidateOne));
				indexes.saveEntities(batch, second, worldState(candidateOne, candidateTwo));
			});

			assertThat(rocks.get(families.authorities(), AUTHORITY.toArray()))
					.isEqualTo(mapper.writeValueAsBytes(candidateTwo));
			rocks.executeAtomicBatch(batch -> indexes.revertEntities(batch, second));
			assertThat(rocks.get(families.authorities(), AUTHORITY.toArray()))
					.isEqualTo(mapper.writeValueAsBytes(candidateOne));
			rocks.executeAtomicBatch(batch -> indexes.revertEntities(batch, first));
			assertThat(rocks.get(families.authorities(), AUTHORITY.toArray()))
					.isEqualTo(mapper.writeValueAsBytes(ancestor));
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	@Test
	void consecutiveValidatorUndoLogsUseTheImmediateCandidateParentState() throws Exception {
		Path databasePath = temporaryDirectory.resolve("validators");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(databasePath)).open(databasePath, families);
		try {
			RocksDBRepository rocks = new RocksDBRepository(database, families);
			ObjectMapper mapper = new JacksonConfig().baseObjectMapper(new Jackson2ObjectMapperBuilder());
			EntityIndexRepository indexes = repository(rocks, mapper);
			ValidatorState ancestor = validator(1);
			ValidatorState losingHead = validator(2);
			ValidatorState candidateOne = validator(3);
			ValidatorState candidateTwo = validator(4);
			Block first = block(201);
			Block second = block(202);

			rocks.executeAtomicBatch(batch -> batch.put(
					families.validators(), VALIDATOR.toArray(), mapper.writeValueAsBytes(losingHead)));
			rocks.executeAtomicBatch(batch -> {
				indexes.saveEntities(batch, first, validatorWorldState(ancestor, candidateOne));
				indexes.saveEntities(batch, second, validatorWorldState(candidateOne, candidateTwo));
			});
			rocks.executeAtomicBatch(batch -> indexes.revertEntities(batch, second));

			assertThat(rocks.get(families.validators(), VALIDATOR.toArray()))
					.isEqualTo(mapper.writeValueAsBytes(candidateOne));
			rocks.executeAtomicBatch(batch -> indexes.revertEntities(batch, first));
			assertThat(rocks.get(families.validators(), VALIDATOR.toArray()))
					.isEqualTo(mapper.writeValueAsBytes(ancestor));
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	private EntityIndexRepository repository(RocksDBRepository rocks, ObjectMapper mapper) {
		return new EntityIndexRepository(
				rocks,
				mapper,
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build(),
				Caffeine.newBuilder().build());
	}

	private WorldState worldState(AuthorityState oldState, AuthorityState newState) {
		WorldState state = mock(WorldState.class);
		StateDiff<AuthorityState> diff = new WorldStateDiff<>(oldState, newState);
		when(state.isMining()).thenReturn(false);
		when(state.getTokenDiffs()).thenReturn(Map.of());
		when(state.getDirtyAuthorities()).thenReturn(Map.of(AUTHORITY, newState));
		when(state.getAuthorityDiffs()).thenReturn(Map.of(AUTHORITY, diff));
		when(state.getAuthoritiesRemovedWithState()).thenReturn(Map.of());
		when(state.getDirtyValidators()).thenReturn(Map.of());
		when(state.getValidatorDiffs()).thenReturn(Map.of());
		when(state.getValidatorsRemovedWithState()).thenReturn(Map.of());
		return state;
	}

	private WorldState validatorWorldState(ValidatorState oldState, ValidatorState newState) {
		WorldState state = mock(WorldState.class);
		StateDiff<ValidatorState> diff = new WorldStateDiff<>(oldState, newState);
		when(state.isMining()).thenReturn(false);
		when(state.getTokenDiffs()).thenReturn(Map.of());
		when(state.getDirtyAuthorities()).thenReturn(Map.of());
		when(state.getAuthorityDiffs()).thenReturn(Map.of());
		when(state.getAuthoritiesRemovedWithState()).thenReturn(Map.of());
		when(state.getDirtyValidators()).thenReturn(Map.of(VALIDATOR, newState));
		when(state.getValidatorDiffs()).thenReturn(Map.of(VALIDATOR, diff));
		when(state.getValidatorsRemovedWithState()).thenReturn(Map.of());
		return state;
	}

	private Block block(int hashValue) {
		Block block = mock(Block.class);
		when(block.getHash()).thenReturn(Hash.fromHexString(String.format("0x%064x", hashValue)));
		return block;
	}

	private AuthorityState authority(int origin) {
		return AuthorityStateImpl.builder()
				.version(AuthorityStateVersion.V1)
				.originTxHash(Hash.fromHexString(String.format("0x%064x", origin)))
				.createdAtBlockHeight(origin)
				.createdAtTimestamp(TIME.plusSeconds(origin))
				.build();
	}

	private ValidatorState validator(int origin) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V1)
				.originTxHash(Hash.fromHexString(String.format("0x%064x", origin)))
				.createdAtBlockHeight(origin)
				.createdAtTimestamp(TIME.plusSeconds(origin))
				.build();
	}

	private BlockchainDbProperties properties(Path databasePath) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(databasePath.toString());
		properties.setRocksdbBlockCacheMb(1);
		properties.setRocksdbWriteBufferMb(1);
		properties.setRocksdbMaxWriteBuffers(2);
		properties.setRocksdbMaxBackgroundJobs(1);
		properties.setRocksdbBlockSizeKb(4);
		properties.setRocksdbDirectReads(false);
		properties.setRocksdbDirectWrites(false);
		properties.setRocksdbBlobEnabled(false);
		return properties;
	}
}
