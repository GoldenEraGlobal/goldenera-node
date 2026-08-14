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
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipVotePayloadImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.BipStateImpl;
import global.goldenera.cryptoj.common.state.impl.BipStateMetadataImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.cryptoj.enums.state.BipStateMetadataVersion;
import global.goldenera.cryptoj.enums.state.BipStateVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.cryptoj.enums.state.BipType;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.handlers.BipVoteHandler;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class BipVoteMiningEconomicsTest {

	private static final Address AUTHORITY = Address.fromHexString("0x0000000000000000000000000000000000000001");
	private static final Address EXISTING_VALIDATOR = Address.fromHexString(
			"0x0000000000000000000000000000000000000002");
	private static final Address PROPOSED_VALIDATOR = Address.fromHexString(
			"0x0000000000000000000000000000000000000003");
	private static final Hash BIP_HASH = Hash.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000011");
	private static final Hash VOTE_HASH = Hash.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000022");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@TempDir
	Path databaseDirectory;

	@Test
	void legacyProposalApprovedAfterForkIsRejectedAndMiningSnapshotRollsBackVote() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(true);
			state.setParams(params());
			state.setMiningWindow(MiningWindowStateImpl.empty(100, 10));
			state.addAuthority(AUTHORITY, AuthorityStateImpl.builder()
					.version(AuthorityStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(0)
					.createdAtTimestamp(TIME)
					.build());
			state.addValidator(EXISTING_VALIDATOR, ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(0)
					.createdAtTimestamp(TIME)
					.build());
			state.setBip(BIP_HASH, legacyValidatorAddBip());

			Tx vote = vote();
			MiningEconomicsActivationService activationService = mock(MiningEconomicsActivationService.class);
			BipVoteHandler voteHandler = new BipVoteHandler(new ValidatorMiningGovernanceService());
			StateProcessor processor = new StateProcessor(List.of(voteHandler), activationService,
					mock(ValidatorMiningPolicyService.class));
			SimpleBlock block = SimpleBlock.builder()
					.height(20)
					.timestamp(TIME.plusSeconds(5))
					.coinbase(PROPOSED_VALIDATOR)
					.build();

			ExecutionResult result = processor.executeMiningBatch(state, block, List.of(vote), state.getParams());

			assertThat(result.getValidTxs()).isEmpty();
			assertThat(result.getInvalidTxs()).containsExactly(vote);
			assertThat(state.getBip(BIP_HASH).getStatus()).isEqualTo(BipStatus.PENDING);
			assertThat(state.getBip(BIP_HASH).getAllVoters()).isEmpty();
			assertThat(state.getBip(BIP_HASH).isActionExecuted()).isFalse();
			assertThat(state.getValidator(PROPOSED_VALIDATOR).exists()).isFalse();
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(1);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(state.getNonce(AUTHORITY).getNonce()).isEqualTo(-1);
		}
	}

	private BipStateImpl legacyValidatorAddBip() {
		TxBipValidatorAddPayloadImpl payload = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V1)
				.address(PROPOSED_VALIDATOR)
				.build();
		BipStateMetadataImpl metadata = BipStateMetadataImpl.builder()
				.version(BipStateMetadataVersion.V1)
				.txVersion(TxVersion.V1)
				.txPayload(payload)
				.build();
		return BipStateImpl.builder()
				.version(BipStateVersion.V1)
				.status(BipStatus.PENDING)
				.isActionExecuted(false)
				.type(BipType.VALIDATOR_ADD)
				.numberOfRequiredVotes(1)
				.approvers(new LinkedHashSet<>())
				.disapprovers(new LinkedHashSet<>())
				.expirationTimestamp(TIME.plusSeconds(3600))
				.metadata(metadata)
				.originTxHash(BIP_HASH)
				.updatedByTxHash(BIP_HASH)
				.updatedAtBlockHeight(5)
				.updatedAtTimestamp(TIME)
				.build();
	}

	private Tx vote() {
		Tx tx = mock(Tx.class);
		when(tx.getType()).thenReturn(TxType.BIP_VOTE);
		when(tx.getSender()).thenReturn(AUTHORITY);
		when(tx.getReferenceHash()).thenReturn(BIP_HASH);
		when(tx.getPayload()).thenReturn(TxBipVotePayloadImpl.builder().type(BipVoteType.APPROVAL).build());
		when(tx.getHash()).thenReturn(VOTE_HASH);
		when(tx.getNonce()).thenReturn(0L);
		when(tx.getFee()).thenReturn(Wei.ZERO);
		when(tx.getSize()).thenReturn(1);
		return tx;
	}

	private NetworkParamsStateImpl params() {
		return NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
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
				.currentValidatorCount(1)
				.currentUnlimitedValidatorCount(1)
				.validatorMiningWindowBlocks(100)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(TIME)
				.build();
	}
}
