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
import static global.goldenera.node.core.mempool.MempoolTestFixtures.CAROL;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.address;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.governance;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.hash;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.vote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStore.AdmissionConstraints;
import global.goldenera.node.core.mempool.MempoolStore.StorageAddResult;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreRbfGovernanceMatrixTest {

	private static final Address TARGET = address(500);
	private static final Address OTHER_TARGET = address(501);
	private static final Address TOKEN = address(600);
	private static final Address OTHER_TOKEN = address(601);

	MempoolStore store;

	@BeforeEach
	void setUp() {
		store = new MempoolStore(new SimpleMeterRegistry(), properties(100),
				mock(ChainHeadStateCache.class), mock(ApplicationEventPublisher.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("governanceConflicts")
	void governanceConflictMatrixRejectsOnlyCanonicalCollision(String name, MempoolEntry first,
			MempoolEntry conflict, MempoolEntry independent) {
		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(conflict, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.addTransaction(independent, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.getAllTxs()).containsExactlyInAnyOrder(first, independent);

		store.removeTransaction(first.getHash());

		assertThat(store.addTransaction(conflict, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
	}

	@Test
	void networkParameterChangesConflictGloballyUntilOwnerIsRemoved() {
		MempoolEntry first = governance(80, ALICE, 1, 100, mock(TxBipNetworkParamsSetPayload.class));
		MempoolEntry second = governance(81, BOB, 1, 100, mock(TxBipNetworkParamsSetPayload.class));
		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW)).matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(second, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		store.removeTransaction(first.getHash());
		assertThat(store.addTransaction(second, 0, MempoolTxAddEvent.AddReason.NEW)).matches(StorageAddResult::isSuccess);
	}

	@Test
	void votesConflictOnlyForSameBipAndVoterAcrossAdmissionRemovalAndRevalidationShape() {
		MempoolEntry first = vote(100, ALICE, 1, 100, hash(700));
		MempoolEntry sameVoterAndBip = vote(101, ALICE, 2, 200, hash(700));
		MempoolEntry otherVoter = vote(102, BOB, 1, 100, hash(700));
		MempoolEntry otherBip = vote(103, ALICE, 3, 100, hash(701));

		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW)).matches(StorageAddResult::isSuccess);
		assertThat(store.hasGovernanceConflict(first, first.getHash())).isFalse();
		assertThat(store.addTransaction(sameVoterAndBip, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.addTransaction(otherVoter, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(otherBip, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		store.removeTransaction(first.getHash());
		assertThat(store.isBipVotePending(hash(700), BOB)).isTrue();
		assertThat(store.isBipVotePending(hash(700), ALICE)).isFalse();
	}

	@Test
	void rbfRecomputesNativeAndTokenReservationsAndFailedReplacementIsAtomic() {
		MempoolEntry original = tokenTransfer(200, ALICE, 1, TOKEN, 70, 10);
		AdmissionConstraints originalBalances = new AdmissionConstraints(
				Wei.valueOf(100), Map.of(TOKEN, Wei.valueOf(100)), null);
		assertThat(store.addTransaction(original, 0, MempoolTxAddEvent.AddReason.NEW, originalBalances))
				.matches(StorageAddResult::isSuccess);

		MempoolEntry unaffordable = tokenTransfer(201, ALICE, 1, TOKEN, 120, 11);
		assertThat(store.addTransaction(unaffordable, 0, MempoolTxAddEvent.AddReason.NEW, originalBalances))
				.isEqualTo(StorageAddResult.INSUFFICIENT_FUNDS);
		assertThat(store.getTxByHash(original.getHash())).containsSame(original);
		assertThat(store.tokenReservation(ALICE, TOKEN,
				tokenTransfer(202, ALICE, 2, TOKEN, 0, 0).getTx(), Wei.valueOf(100)).reserved())
				.isEqualTo(Wei.valueOf(70));

		MempoolEntry replacement = tokenTransfer(203, ALICE, 1, OTHER_TOKEN, 40, 11);
		AdmissionConstraints replacementBalances = new AdmissionConstraints(
				Wei.valueOf(100), Map.of(OTHER_TOKEN, Wei.valueOf(100)), null);
		assertThat(store.addTransaction(replacement, 0, MempoolTxAddEvent.AddReason.NEW, replacementBalances))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.getTxByHash(original.getHash())).isEmpty();
		assertThat(store.tokenReservation(ALICE, TOKEN,
				tokenTransfer(204, ALICE, 2, TOKEN, 0, 0).getTx(), Wei.valueOf(100)).reserved())
				.isEqualTo(Wei.ZERO);
		assertThat(store.tokenReservation(ALICE, OTHER_TOKEN,
				tokenTransfer(205, ALICE, 2, OTHER_TOKEN, 0, 0).getTx(), Wei.valueOf(100)).reserved())
				.isEqualTo(Wei.valueOf(40));
	}

	@Test
	void exactRbfBoundaryWorksForZeroAndRoundingFeesWithoutMutatingOnFailure() {
		for (long oldFee : List.of(0L, 1L, 9L, 10L, 101L)) {
			store.clear();
			MempoolEntry original = transfer(300 + (int) oldFee, ALICE, 1, oldFee);
			assertThat(store.addTransaction(original, 0, MempoolTxAddEvent.AddReason.NEW))
					.matches(StorageAddResult::isSuccess);
			long exactMinimum = oldFee == 0 ? 1 : oldFee + Math.max(1, (oldFee + 9) / 10);
			if (exactMinimum > 0) {
				MempoolEntry below = transfer(500 + (int) oldFee, ALICE, 1, exactMinimum - 1);
				assertThat(store.addTransaction(below, 0, MempoolTxAddEvent.AddReason.NEW))
						.isEqualTo(StorageAddResult.FAILED_FEE_TOO_LOW);
				assertThat(store.getTxByHash(original.getHash())).containsSame(original);
			}
			MempoolEntry exact = transfer(700 + (int) oldFee, ALICE, 1, exactMinimum);
			assertThat(store.addTransaction(exact, 0, MempoolTxAddEvent.AddReason.NEW))
					.matches(StorageAddResult::isSuccess);
			assertThat(store.getTxByHash(original.getHash())).isEmpty();
		}
	}

	private static Stream<Arguments> governanceConflicts() {
		TxBipAuthorityAddPayload authorityAdd = addressPayload(TxBipAuthorityAddPayload.class, TARGET);
		TxBipAuthorityRemovePayload authorityRemove = addressPayload(TxBipAuthorityRemovePayload.class, TARGET);
		TxBipAuthorityAddPayload otherAuthority = addressPayload(TxBipAuthorityAddPayload.class, OTHER_TARGET);

		TxBipValidatorAddPayload validatorAdd = addressPayload(TxBipValidatorAddPayload.class, TARGET);
		TxBipValidatorRemovePayload validatorRemove = addressPayload(TxBipValidatorRemovePayload.class, TARGET);
		TxBipValidatorAddPayload otherValidator = addressPayload(TxBipValidatorAddPayload.class, OTHER_TARGET);
		TxBipValidatorMiningPolicySetPayload validatorPolicy = policyPayload(TARGET);
		TxBipValidatorMiningPolicySetPayload otherValidatorPolicy = policyPayload(OTHER_TARGET);

		TxBipAddressAliasAddPayload aliasAdd = aliasPayload(TxBipAddressAliasAddPayload.class, "target");
		TxBipAddressAliasRemovePayload aliasRemove = aliasPayload(TxBipAddressAliasRemovePayload.class, "target");
		TxBipAddressAliasAddPayload otherAlias = aliasPayload(TxBipAddressAliasAddPayload.class, "other");

		TxBipTokenUpdatePayload tokenUpdate = tokenPayload(TOKEN);
		TxBipTokenUpdatePayload sameTokenUpdate = tokenPayload(TOKEN);
		TxBipTokenUpdatePayload otherTokenUpdate = tokenPayload(OTHER_TOKEN);

		return Stream.of(
				row("authority add/remove", 1, authorityAdd, authorityRemove, otherAuthority),
				row("validator add/remove", 10, validatorAdd, validatorRemove, otherValidator),
				row("validator add/policy", 15, validatorAdd, validatorPolicy, otherValidatorPolicy),
				row("alias add/remove", 20, aliasAdd, aliasRemove, otherAlias),
				row("token update", 30, tokenUpdate, sameTokenUpdate, otherTokenUpdate));
	}

	private static Arguments row(String name, int id, TxPayload first, TxPayload conflict, TxPayload independent) {
		return Arguments.of(name, governance(id, ALICE, 1, 100, first),
				governance(id + 1, BOB, 1, 100, conflict), governance(id + 2, CAROL, 1, 100, independent));
	}

	private static <T extends TxPayload> T addressPayload(Class<T> type, Address target) {
		T payload = mock(type);
		if (payload instanceof TxBipAuthorityAddPayload value) {
			when(value.getAddress()).thenReturn(target);
		} else if (payload instanceof TxBipAuthorityRemovePayload value) {
			when(value.getAddress()).thenReturn(target);
		} else if (payload instanceof TxBipValidatorAddPayload value) {
			when(value.getAddress()).thenReturn(target);
		} else if (payload instanceof TxBipValidatorRemovePayload value) {
			when(value.getAddress()).thenReturn(target);
		}
		return payload;
	}

	private static <T extends TxPayload> T aliasPayload(Class<T> type, String alias) {
		T payload = mock(type);
		if (payload instanceof TxBipAddressAliasAddPayload value) {
			when(value.getAlias()).thenReturn(alias);
		} else if (payload instanceof TxBipAddressAliasRemovePayload value) {
			when(value.getAlias()).thenReturn(alias);
		}
		return payload;
	}

	private static TxBipTokenUpdatePayload tokenPayload(Address token) {
		TxBipTokenUpdatePayload payload = mock(TxBipTokenUpdatePayload.class);
		when(payload.getTokenAddress()).thenReturn(token);
		return payload;
	}

	private static TxBipValidatorMiningPolicySetPayload policyPayload(Address validator) {
		TxBipValidatorMiningPolicySetPayload payload = mock(TxBipValidatorMiningPolicySetPayload.class);
		when(payload.getValidatorAddress()).thenReturn(validator);
		return payload;
	}

	private MempoolEntry tokenTransfer(int id, Address sender, long nonce, Address token, long amount, long fee) {
		MempoolEntry entry = transfer(id, sender, nonce, fee);
		when(entry.getTx().getTokenAddress()).thenReturn(token);
		when(entry.getTx().getAmount()).thenReturn(Wei.valueOf(amount));
		return entry;
	}
}
