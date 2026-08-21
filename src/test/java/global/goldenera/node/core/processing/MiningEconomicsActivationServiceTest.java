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
package global.goldenera.node.core.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;

class MiningEconomicsActivationServiceTest {

	private static final Address VALIDATOR = Address.fromHexString("0x0000000000000000000000000000000000000001");
	private static final Instant STATE_TIME = Instant.parse("2026-01-01T00:00:00Z");
	private final MiningEconomicsActivationService service = new MiningEconomicsActivationService();

	@TempDir
	Path databaseDirectory;

	@Test
	void migratesV1ParamsAndCreatesEmptyWindowWithoutRewritingValidators() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState legacy = storage.createEmpty(false);
			NetworkParamsState legacyParams = legacyParams(1);
			ValidatorStateImpl legacyValidator = ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(0)
					.createdAtTimestamp(STATE_TIME)
					.build();
			legacy.setParams(legacyParams);
			legacy.addValidator(VALIDATOR, legacyValidator);
			Hash legacyRoot = storage.persist(legacy);
			Hash validatorRootBefore = Hash.wrap(legacy.getValidatorTrie().getRootHash());

			WorldState activated = storage.reload(legacyRoot, false);
			service.applyIfNeeded(activated, 10, true, 1000);

			assertThat(activated.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V2);
			assertThat(activated.getParams().getValidatorMiningWindowBlocks()).isEqualTo(1000);
			assertThat(activated.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(activated.getParams().getUpdatedByTxHash()).isEqualTo(legacyParams.getUpdatedByTxHash());
			assertThat(activated.getParams().getUpdatedAtBlockHeight())
					.isEqualTo(legacyParams.getUpdatedAtBlockHeight());
			assertThat(activated.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(activated.getMiningWindow().getLastUpdatedBlockHeight()).isEqualTo(10);
			activated.calculateRootHash();
			assertThat(Hash.wrap(activated.getValidatorTrie().getRootHash())).isEqualTo(validatorRootBefore);
			assertThat(activated.getValidator(VALIDATOR)).isEqualTo(legacyValidator);
			assertThat(activated.getValidator(VALIDATOR).getVersion()).isEqualTo(ValidatorStateVersion.V1);
		}
	}

	@Test
	void inactiveForkLeavesLegacyRootUnchanged() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState legacy = storage.createEmpty(false);
			legacy.setParams(legacyParams(1));
			Hash legacyRoot = storage.persist(legacy);

			WorldState beforeFork = storage.reload(legacyRoot, false);
			service.applyIfNeeded(beforeFork, 9, false, 1000);

			assertThat(beforeFork.calculateRootHash()).isEqualTo(legacyRoot);
			assertThat(beforeFork.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V1);
			assertThat(beforeFork.getMiningWindow()).isEqualTo(MiningWindowStateImpl.ZERO);
		}
	}

	@Test
	void activationStatePersistsReloadsAndIsIdempotent() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState legacy = storage.createEmpty(false);
			legacy.setParams(legacyParams(2));
			Hash legacyRoot = storage.persist(legacy);
			WorldState activated = storage.reload(legacyRoot, false);
			service.applyIfNeeded(activated, 10, true, 100);
			Hash activatedRoot = storage.persist(activated);

			WorldState reloaded = storage.reload(activatedRoot, false);
			service.applyIfNeeded(reloaded, 11, true, 100);

			assertThat(reloaded.calculateRootHash()).isEqualTo(activatedRoot);
			assertThat(reloaded.getMiningWindow().getWindowSize()).isEqualTo(100);
			assertThat(reloaded.getMiningWindow().getLastUpdatedBlockHeight()).isEqualTo(10);
		}
	}

	@Test
	void miningSnapshotRevertsWindowAndBranchesRemainIsolated() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState legacy = storage.createEmpty(false);
			legacy.setParams(legacyParams(1));
			Hash legacyRoot = storage.persist(legacy);

			WorldState base = storage.reload(legacyRoot, false);
			service.applyIfNeeded(base, 10, true, 100);
			Hash baseRoot = storage.persist(base);

			WorldState mining = storage.reload(baseRoot, true);
			Object snapshot = mining.createSnapshot();
			MiningWindowState changed = ((MiningWindowStateImpl) mining.getMiningWindow()).append(VALIDATOR, 11);
			mining.setMiningWindow(changed);
			mining.revertToSnapshot(snapshot);
			assertThat(mining.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();

			WorldState branchA = storage.reload(baseRoot, false);
			branchA.setMiningWindow(((MiningWindowStateImpl) branchA.getMiningWindow()).append(VALIDATOR, 11));
			Hash branchARoot = storage.persist(branchA);
			Address other = Address.fromHexString("0x0000000000000000000000000000000000000002");
			WorldState branchB = storage.reload(baseRoot, false);
			branchB.setMiningWindow(((MiningWindowStateImpl) branchB.getMiningWindow()).append(other, 11));
			Hash branchBRoot = storage.persist(branchB);

			assertThat(branchARoot).isNotEqualTo(branchBRoot);
			assertThat(storage.reload(branchARoot, false).getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(VALIDATOR);
			assertThat(storage.reload(branchBRoot, false).getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(other);
		}
	}

	@Test
	void rejectsInconsistentAlreadyMigratedState() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(false);
			state.setParams(((NetworkParamsStateImpl) legacyParams(1)).toBuilder()
					.version(NetworkParamsStateVersion.V2)
					.currentUnlimitedValidatorCount(1)
					.validatorMiningWindowBlocks(100)
					.build());

			assertThatThrownBy(() -> service.applyIfNeeded(state, 10, true, 100))
					.isInstanceOf(GEValidationException.class)
					.hasMessageContaining("missing or inconsistent");
		}
	}

	@Test
	void readinessRejectsAnUnmigratedActiveCanonicalHead() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(false);
			state.setParams(legacyParams(1));

			assertThatCode(() -> service.assertHeadReady(state, 731_502)).doesNotThrowAnyException();
			assertThatThrownBy(() -> service.assertHeadReady(state, 731_503))
					.isInstanceOf(GEValidationException.class)
					.hasMessageContaining("missing the mining economics activation transition");
		}
	}

	@Test
	void miningWindowParticipatesInDiffPrepareAndRollbackLifecycle() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(false);
			MiningWindowState empty = MiningWindowStateImpl.empty(100, 10);
			state.setMiningWindow(empty);

			assertThat(state.getMiningWindowDiff().getOldValue()).isEqualTo(MiningWindowStateImpl.ZERO);
			assertThat(state.getMiningWindowDiff().getNewValue()).isEqualTo(empty);
			state.calculateRootHash();
			state.prepareForNextBlock();
			assertThat(state.getMiningWindow()).isEqualTo(empty);
			state.setMiningWindow(((MiningWindowStateImpl) empty).append(VALIDATOR, 11));
			state.rollback();
			assertThat(state.getMiningWindow()).isEqualTo(empty);
		}
	}

	private NetworkParamsStateImpl legacyParams(long validatorCount) {
		return NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V1)
				.blockReward(Wei.valueOf(10))
				.blockRewardPoolAddress(Address.ZERO)
				.targetMiningTimeMs(30_000)
				.asertHalfLifeBlocks(64)
				.asertAnchorHeight(0)
				.minDifficulty(BigInteger.valueOf(5000))
				.minTxBaseFee(Wei.valueOf(200))
				.minTxByteFee(Wei.valueOf(5))
				.updatedByTxHash(Hash.ZERO)
				.currentAuthorityCount(1)
				.currentValidatorCount(validatorCount)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(STATE_TIME)
				.build();
	}
}
