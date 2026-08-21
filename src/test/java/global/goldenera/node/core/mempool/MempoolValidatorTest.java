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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.BOB;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.governance;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.hash;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.vote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.impl.MiningRewardMaturityStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.MempoolValidator.ValidationStatus;
import global.goldenera.node.core.mempool.MempoolManager.MempoolReasonCode;
import global.goldenera.node.core.mempool.MempoolStore.ReservationSnapshot;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolValidatorTest {

	WorldState worldState;
	MempoolStore store;
	MempoolValidator validator;
	ChainClock chainClock;
	Block block;
	NetworkParamsState params;

	@BeforeEach
	void setUp() {
		worldState = mock(WorldState.class, RETURNS_DEEP_STUBS);
		store = mock(MempoolStore.class);
		ChainHeadStateCache chainHead = mock(ChainHeadStateCache.class);
		when(chainHead.getHeadState()).thenReturn(worldState);
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock storedBlock = mock(StoredBlock.class);
		block = mock(Block.class, RETURNS_DEEP_STUBS);
		when(block.getHeight()).thenReturn(731_503L);
		when(block.getHeader().getTimestamp()).thenReturn(Instant.now().minusSeconds(10));
		when(storedBlock.getBlock()).thenReturn(block);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(storedBlock);

		MempoolProperties properties = MempoolTestFixtures.properties(100);
		params = mock(NetworkParamsState.class);
		when(params.getMinTxBaseFee()).thenReturn(Wei.ZERO);
		when(params.getMinTxByteFee()).thenReturn(Wei.ZERO);
		when(params.getValidatorMiningWindowBlocks()).thenReturn(100L);
		when(params.getCurrentValidatorCount()).thenReturn(1L);
		when(params.getCurrentUnlimitedValidatorCount()).thenReturn(1L);
		when(worldState.getParams()).thenReturn(params);
		when(worldState.getMiningRewardMaturity(anyLong())).thenReturn(MiningRewardMaturityStateImpl.ZERO);
		AccountNonceState nonce = mock(AccountNonceState.class);
		when(nonce.getNonce()).thenReturn(0L);
		when(worldState.getNonce(any(Address.class))).thenReturn(nonce);
		when(worldState.getAuthority(ALICE).exists()).thenReturn(true);
		when(store.nativeReservation(any(Address.class), any(Tx.class), any(Wei.class)))
				.thenAnswer(invocation -> affordable(invocation.getArgument(2)));
		when(store.tokenReservation(any(Address.class), any(Address.class), any(Tx.class), any(Wei.class)))
				.thenAnswer(invocation -> affordable(invocation.getArgument(3)));
		chainClock = mock(ChainClock.class);
		when(chainClock.earliestNextBlockTimestamp(any())).thenReturn(Instant.now());
		validator = new MempoolValidator(new SimpleMeterRegistry(), chainHead, chainQuery, properties, store,
				mock(TxValidator.class), chainClock);
	}

	@Test
	void cumulativeNativeSpendIncludesPendingQueue() {
		MempoolEntry pending = nativeTransfer(1, 1, 80, 10);
		MempoolEntry candidate = nativeTransfer(2, 2, 60, 10);
		when(store.getTxsBySender(ALICE)).thenReturn(List.of(pending));
		balance(Address.NATIVE_TOKEN, 150);
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(150))))
				.thenReturn(reservation(90, 0, 70, 150));

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isNull();
		assertThat(result.getErrorMessage()).contains("Insufficient native funds");
	}

	@Test
	void rbfCandidateExcludesReplacedNonceFromCumulativeSpend() {
		MempoolEntry replaced = nativeTransfer(1, 1, 1000, 10);
		MempoolEntry candidate = nativeTransfer(2, 1, 60, 10);
		when(store.getTxsBySender(ALICE)).thenReturn(List.of(replaced));
		balance(Address.NATIVE_TOKEN, 70);
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(70))))
				.thenReturn(reservation(1_010, 1_010, 70, 70));

		assertThat(admit(candidate).getStatus()).isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void cumulativeCustomTokenSpendIsRejectedWhileNativeFeeIsReservedSeparately() {
		Address token = MempoolTestFixtures.address(90);
		MempoolEntry pending = customTransfer(1, 1, token, 50, 5);
		MempoolEntry candidate = customTransfer(2, 2, token, 60, 5);
		when(store.getTxsBySender(ALICE)).thenReturn(List.of(pending));
		balance(Address.NATIVE_TOKEN, 100);
		balance(token, 100);
		TokenState tokenState = mock(TokenState.class);
		when(tokenState.exists()).thenReturn(true);
		when(worldState.getToken(token)).thenReturn(tokenState);
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(100))))
				.thenReturn(reservation(5, 0, 5, 100));
		when(store.tokenReservation(eq(ALICE), eq(token), eq(candidate.getTx()), eq(Wei.valueOf(100))))
				.thenReturn(reservation(50, 0, 60, 100));

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("Insufficient token funds");
	}

	@Test
	void governanceFeeMustBeCoveredByNativeBalance() {
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		balance(Address.NATIVE_TOKEN, 9);
		when(store.nativeReservation(eq(ALICE), eq(vote.getTx()), eq(Wei.valueOf(9))))
				.thenReturn(reservation(0, 0, 10, 9));

		MempoolValidationResult result = admit(vote);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("governance fee");
	}

	@Test
	void nativeAdmissionUsesSpendableBalanceInsteadOfTotalBalance() {
		MempoolEntry candidate = nativeTransfer(1, 1, 25, 10);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(100));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(30));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.ZERO);
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(30))))
				.thenReturn(reservation(0, 0, 35, 30));

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.INSUFFICIENT_SPENDABLE_BALANCE);
		assertThat(result.getErrorMessage()).contains("Insufficient native funds");
	}

	@Test
	void governanceAdmissionReportsLockedMiningRewardAsSpendabilityCause() {
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(100));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(5));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.ZERO);
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(store.nativeReservation(eq(ALICE), eq(vote.getTx()), eq(Wei.valueOf(5))))
				.thenReturn(reservation(0, 0, 10, 5));

		MempoolValidationResult result = admit(vote);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.INSUFFICIENT_SPENDABLE_BALANCE);
	}

	@Test
	void nativeTransferCanUseRewardMaturingAtCandidateBlockHeight() {
		MempoolEntry candidate = nativeTransfer(1, 1, 25, 10);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(40));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(5));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.ZERO);
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(worldState.getMiningRewardMaturity(11)).thenReturn(
				MiningRewardMaturityStateImpl.empty().addReward(ALICE, Wei.valueOf(35)));
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(40))))
				.thenReturn(reservation(0, 0, 35, 40));

		assertThat(admit(candidate).getStatus()).isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void nativeTransferCannotUseRewardMaturingAfterCandidateBlockHeight() {
		MempoolEntry candidate = nativeTransfer(1, 1, 25, 10);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(40));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(5));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.ZERO);
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(worldState.getMiningRewardMaturity(12)).thenReturn(
				MiningRewardMaturityStateImpl.empty().addReward(ALICE, Wei.valueOf(35)));
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(5))))
				.thenReturn(reservation(0, 0, 35, 5));

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.INSUFFICIENT_SPENDABLE_BALANCE);
	}

	@Test
	void cancelledMaturityIsNotProjectedAsSpendableAtCandidateHeight() {
		MempoolEntry candidate = nativeTransfer(1, 1, 1, 10);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(5));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(5));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.valueOf(35));
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(worldState.getMiningRewardMaturity(11)).thenReturn(
				MiningRewardMaturityStateImpl.empty().addReward(ALICE, Wei.valueOf(35)));
		when(store.nativeReservation(eq(ALICE), eq(candidate.getTx()), eq(Wei.valueOf(5))))
				.thenReturn(reservation(0, 0, 11, 5));

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isNull();
	}

	@Test
	void expiredVoteIsRejectedEvenWhenStoredStatusIsPending() {
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		balance(Address.NATIVE_TOKEN, 100);
		BipState bip = mock(BipState.class);
		when(bip.exists()).thenReturn(true);
		when(bip.getStatus()).thenReturn(BipStatus.PENDING);
		when(bip.getExpirationTimestamp()).thenReturn(Instant.now().minusSeconds(1));
		when(bip.getAllVoters()).thenReturn(new LinkedHashSet<>());
		when(worldState.getBip(hash(50))).thenReturn(bip);

		MempoolValidationResult result = admit(vote);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("expired");
	}

	@Test
	void deterministicChainTimeDoesNotExpireAValidVoteAgainstTheHostWallClock() {
		Instant deterministicNextBlock = Instant.parse("2023-11-14T22:13:41Z");
		when(chainClock.earliestNextBlockTimestamp(any())).thenReturn(deterministicNextBlock);
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		balance(Address.NATIVE_TOKEN, 100);
		BipState bip = mock(BipState.class);
		when(bip.exists()).thenReturn(true);
		when(bip.getStatus()).thenReturn(BipStatus.PENDING);
		when(bip.getExpirationTimestamp()).thenReturn(deterministicNextBlock.plusSeconds(60));
		when(bip.getAllVoters()).thenReturn(new LinkedHashSet<>());
		when(worldState.getBip(hash(50))).thenReturn(bip);

		MempoolValidationResult result = admit(vote);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void pendingVoteConflictsOnAdmissionButNotDuringSelfRevalidation() {
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		balance(Address.NATIVE_TOKEN, 100);
		BipState bip = pendingBip();
		when(worldState.getBip(hash(50))).thenReturn(bip);
		when(store.isBipVotePending(hash(50), ALICE)).thenReturn(true);
		when(store.hasGovernanceConflict(any(MempoolEntry.class), any())).thenReturn(true);

		assertThat(admit(vote).getStatus()).isEqualTo(ValidationStatus.GOVERNANCE_DUPLICATE);
		assertThat(validator.revalidateAgainstChain(vote).getStatus()).isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void stateInfrastructureExceptionIsClassifiedTransient() {
		MempoolEntry entry = nativeTransfer(1, 1, 1, 1);
		when(worldState.getNonce(ALICE)).thenThrow(new IllegalStateException("rocks unavailable"));

		assertThat(admit(entry).getStatus()).isEqualTo(ValidationStatus.TRANSIENT_ERROR);
	}

	@Test
	void postForkAdmissionRejectsLegacyValidatorAddAndAcceptsCanonicalV2() {
		balance(Address.NATIVE_TOKEN, 100);
		Address validatorAddress = MempoolTestFixtures.address(91);
		TxBipValidatorAddPayloadImpl legacy = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V1)
				.address(validatorAddress)
				.build();
		TxBipValidatorAddPayloadImpl versionTwo = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(validatorAddress)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(4000L)
				.build();

		assertThat(admit(governance(90, ALICE, 1, 10, legacy)).getStatus())
				.isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(admit(governance(91, ALICE, 1, 10, versionTwo)).getStatus())
				.isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void firstLimitedValidatorUsesStableLastUnlimitedReasonCode() {
		balance(Address.NATIVE_TOKEN, 100);
		when(worldState.getParams().getCurrentValidatorCount()).thenReturn(0L);
		when(worldState.getParams().getCurrentUnlimitedValidatorCount()).thenReturn(0L);
		Address validatorAddress = MempoolTestFixtures.address(92);
		TxBipValidatorAddPayloadImpl limited = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(validatorAddress)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(4000L)
				.build();

		MempoolValidationResult result = admit(governance(92, ALICE, 1, 10, limited));

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.LAST_UNLIMITED_REQUIRED);
	}

	@Test
	void limitedPolicyWithZeroIntegerQuotaUsesStableReasonCode() {
		balance(Address.NATIVE_TOKEN, 100);
		Address validatorAddress = MempoolTestFixtures.address(93);
		TxBipValidatorAddPayloadImpl limited = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(validatorAddress)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(1L)
				.build();

		MempoolValidationResult result = admit(governance(93, ALICE, 1, 10, limited));

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.LIMITED_QUOTA_ZERO);
	}

	@Test
	void activationCandidateUsesConfiguredWindowInsteadOfLegacyParentZero() {
		balance(Address.NATIVE_TOKEN, 100);
		when(block.getHeight()).thenReturn(731_502L);
		when(params.getVersion()).thenReturn(NetworkParamsStateVersion.V1);
		when(params.getValidatorMiningWindowBlocks()).thenReturn(0L);
		Address validatorAddress = MempoolTestFixtures.address(94);
		TxBipValidatorAddPayloadImpl limited = TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(validatorAddress)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(100L)
				.build();

		MempoolValidationResult result = admit(governance(94, ALICE, 1, 10, limited));

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.VALID);
	}

	@Test
	void networkParamsVersionMismatchIsNotReportedAsWindowBoundsFailure() {
		balance(Address.NATIVE_TOKEN, 100);
		TxBipNetworkParamsSetPayloadImpl legacy = TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V1)
				.build();

		MempoolValidationResult result = admit(governance(95, ALICE, 1, 10, legacy));

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getReasonCode()).isEqualTo(MempoolReasonCode.VALIDATION_STATELESS_INVALID);
	}

	private MempoolValidationResult admit(MempoolEntry entry) {
		return validator.validateAgainstChainAndMempool(entry, MempoolTxAddEvent.AddReason.NEW, true);
	}

	private MempoolEntry nativeTransfer(int id, long nonce, long amount, long fee) {
		MempoolEntry entry = transfer(id, ALICE, nonce, fee);
		Tx tx = entry.getTx();
		when(tx.getTokenAddress()).thenReturn(Address.NATIVE_TOKEN);
		when(tx.getAmount()).thenReturn(Wei.valueOf(amount));
		when(tx.getRecipient()).thenReturn(BOB);
		return entry;
	}

	private MempoolEntry customTransfer(int id, long nonce, Address token, long amount, long fee) {
		MempoolEntry entry = transfer(id, ALICE, nonce, fee);
		Tx tx = entry.getTx();
		when(tx.getTokenAddress()).thenReturn(token);
		when(tx.getAmount()).thenReturn(Wei.valueOf(amount));
		when(tx.getRecipient()).thenReturn(BOB);
		return entry;
	}

	private void balance(Address token, long value) {
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(value));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(value));
		when(balance.getPendingMiningRewardCancellation()).thenReturn(Wei.ZERO);
		when(worldState.getBalance(ALICE, token)).thenReturn(balance);
	}

	private BipState pendingBip() {
		BipState bip = mock(BipState.class);
		when(bip.exists()).thenReturn(true);
		when(bip.getStatus()).thenReturn(BipStatus.PENDING);
		when(bip.getExpirationTimestamp()).thenReturn(Instant.now().plusSeconds(60));
		when(bip.getAllVoters()).thenReturn(new LinkedHashSet<>());
		return bip;
	}

	private ReservationSnapshot affordable(Wei available) {
		return new ReservationSnapshot(Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.ZERO, available);
	}

	private ReservationSnapshot reservation(long reserved, long replacing, long candidate, long available) {
		return new ReservationSnapshot(
				Wei.valueOf(reserved), Wei.valueOf(replacing), Wei.valueOf(candidate),
				Wei.valueOf(reserved - replacing + candidate), Wei.valueOf(available));
	}
}
