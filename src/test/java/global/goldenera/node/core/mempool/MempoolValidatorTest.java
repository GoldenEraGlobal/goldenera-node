package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.BOB;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.hash;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.vote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
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
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.MempoolValidator.ValidationStatus;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolValidatorTest {

	WorldState worldState;
	MempoolStore store;
	MempoolValidator validator;

	@BeforeEach
	void setUp() {
		worldState = mock(WorldState.class, RETURNS_DEEP_STUBS);
		store = mock(MempoolStore.class);
		ChainHeadStateCache chainHead = mock(ChainHeadStateCache.class);
		when(chainHead.getHeadState()).thenReturn(worldState);
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock storedBlock = mock(StoredBlock.class);
		Block block = mock(Block.class, RETURNS_DEEP_STUBS);
		when(block.getHeight()).thenReturn(10L);
		when(block.getHeader().getTimestamp()).thenReturn(Instant.now().minusSeconds(10));
		when(storedBlock.getBlock()).thenReturn(block);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(storedBlock);

		MempoolProperties properties = MempoolTestFixtures.properties(100);
		NetworkParamsState params = mock(NetworkParamsState.class);
		when(params.getMinTxBaseFee()).thenReturn(Wei.ZERO);
		when(params.getMinTxByteFee()).thenReturn(Wei.ZERO);
		when(worldState.getParams()).thenReturn(params);
		AccountNonceState nonce = mock(AccountNonceState.class);
		when(nonce.getNonce()).thenReturn(0L);
		when(worldState.getNonce(any(Address.class))).thenReturn(nonce);
		when(worldState.getAuthority(ALICE).exists()).thenReturn(true);
		validator = new MempoolValidator(new SimpleMeterRegistry(), chainHead, chainQuery, properties, store,
				mock(TxValidator.class));
	}

	@Test
	void cumulativeNativeSpendIncludesPendingQueue() {
		MempoolEntry pending = nativeTransfer(1, 1, 80, 10);
		MempoolEntry candidate = nativeTransfer(2, 2, 60, 10);
		when(store.getTxsBySender(ALICE)).thenReturn(List.of(pending));
		balance(Address.NATIVE_TOKEN, 150);

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("Insufficient native funds");
	}

	@Test
	void rbfCandidateExcludesReplacedNonceFromCumulativeSpend() {
		MempoolEntry replaced = nativeTransfer(1, 1, 1000, 10);
		MempoolEntry candidate = nativeTransfer(2, 1, 60, 10);
		when(store.getTxsBySender(ALICE)).thenReturn(List.of(replaced));
		balance(Address.NATIVE_TOKEN, 70);

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

		MempoolValidationResult result = admit(candidate);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("Insufficient token balance");
	}

	@Test
	void governanceFeeMustBeCoveredByNativeBalance() {
		MempoolEntry vote = vote(1, ALICE, 1, 10, hash(50));
		balance(Address.NATIVE_TOKEN, 9);

		MempoolValidationResult result = admit(vote);

		assertThat(result.getStatus()).isEqualTo(ValidationStatus.STATE_INVALID);
		assertThat(result.getErrorMessage()).contains("governance fee");
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
}
