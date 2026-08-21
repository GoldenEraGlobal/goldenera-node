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
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class StateProcessorMiningWindowIntegrationTest {

	private static final PrivateKey MINER_KEY = PrivateKey.wrap(Bytes32.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000001"));
	private static final Address MINER = MINER_KEY.getAddress();
	private static final Address BENEFICIARY = Address.fromHexString(
			"0x00000000000000000000000000000000000000f0");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@TempDir
	Path databaseDirectory;

	@Test
	void emptyUnlimitedBlockProducesSameWindowRootForMinerAndValidator() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState base = storage.createEmpty(false);
			base.setParams(params(NetworkParamsStateVersion.V2, 1));
			base.setMiningWindow(MiningWindowStateImpl.empty(100, 10));
			base.addValidator(MINER, validator(ValidatorStateVersion.V2));
			Hash baseRoot = storage.persist(base);

			WorldState validationState = storage.reload(baseRoot, false);
			WorldState miningState = storage.reload(baseRoot, true);
			ValidatorMiningPolicyService policyService = new ValidatorMiningPolicyService();
			StateProcessor processor = new StateProcessor(
					List.of(), mock(MiningEconomicsActivationService.class), policyService);
			SimpleBlock block = signedBlock(731_504);

			processor.executeTransactions(validationState, block, List.of(), validationState.getParams());
			processor.executeMiningBatch(miningState, block, List.of(), miningState.getParams());

			assertThat(validationState.getMiningWindow().getOrderedValidatorIdentities()).containsExactly(MINER);
			assertThat(miningState.getMiningWindow()).isEqualTo(validationState.getMiningWindow());
			assertThat(miningState.calculateRootHash()).isEqualTo(validationState.calculateRootHash());
		}
	}

	@Test
	void activationBlockMigratesButRemainsEmptyAndSignedIdentityComesFromHeaderNotCoinbase() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(false);
			state.setParams(params(NetworkParamsStateVersion.V1, 1));
			state.addValidator(MINER, validator(ValidatorStateVersion.V1));
			ValidatorMiningPolicyService policyService = new ValidatorMiningPolicyService();
			StateProcessor processor = new StateProcessor(
					List.of(), new MiningEconomicsActivationService(), policyService);
			SimpleBlock block = signedBlock(731_503);

			processor.executeTransactions(state, block, List.of(), state.getParams());

			assertThat(block.getIdentity()).isEqualTo(MINER);
			assertThat(block.getIdentity()).isNotEqualTo(block.getCoinbase());
			assertThat(state.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V2);
			assertThat(state.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(state.getMiningWindow().getLastUpdatedBlockHeight()).isEqualTo(731_503);
		}
	}

	private SimpleBlock signedBlock(long height) {
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(TIME.plusSeconds(height))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(BENEFICIARY)
				.nonce(1)
				.build();
		BlockHeaderImpl signed = unsigned.toBuilder()
				.signature(MINER_KEY.sign(BlockHeaderUtil.hashForSigning(unsigned)))
				.build();
		return new SimpleBlock(signed);
	}

	private NetworkParamsStateImpl params(NetworkParamsStateVersion version, long validatorCount) {
		NetworkParamsStateImpl.NetworkParamsStateImplBuilder builder = NetworkParamsStateImpl.builder()
				.version(version)
				.blockReward(Wei.ZERO)
				.blockRewardPoolAddress(Address.ZERO)
				.targetMiningTimeMs(30_000)
				.asertHalfLifeBlocks(64)
				.asertAnchorHeight(0)
				.minDifficulty(BigInteger.ONE)
				.minTxBaseFee(Wei.ZERO)
				.minTxByteFee(Wei.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.currentAuthorityCount(1)
				.currentValidatorCount(validatorCount)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(TIME);
		if (version == NetworkParamsStateVersion.V2) {
			builder.currentUnlimitedValidatorCount(validatorCount)
					.validatorMiningWindowBlocks(100);
		}
		return builder.build();
	}

	private ValidatorStateImpl validator(ValidatorStateVersion version) {
		ValidatorStateImpl.ValidatorStateImplBuilder builder = ValidatorStateImpl.builder()
				.version(version)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(0)
				.createdAtTimestamp(TIME);
		if (version == ValidatorStateVersion.V2) {
			builder.miningLimitMode(MiningLimitMode.UNLIMITED)
					.maxMiningShareBps(0)
					.policyUpdatedByTxHash(Hash.ZERO)
					.policyUpdatedAtBlockHeight(0)
					.policyUpdatedAtTimestamp(TIME);
		}
		return builder.build();
	}
}
