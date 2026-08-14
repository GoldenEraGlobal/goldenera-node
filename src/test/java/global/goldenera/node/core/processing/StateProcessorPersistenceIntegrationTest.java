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

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.handlers.TransferHandler;
import global.goldenera.node.core.processing.handlers.TxHandler;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class StateProcessorPersistenceIntegrationTest {

	private static final Instant BLOCK_TIME = Instant.parse("2026-01-01T00:00:00Z");
	private static final Address MINER = Address.fromHexString("0x00000000000000000000000000000000000000f0");

	@TempDir
	Path databaseDirectory;

	@Test
	void nativeTransferDeductsAmountAndFeeCreditsRecipientAndMinerAndPersists() throws Exception {
		PrivateKey senderKey = key(1);
		Address sender = senderKey.getAddress();
		Address recipient = key(2).getAddress();
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = fundedState(storage, sender, Address.NATIVE_TOKEN, Wei.valueOf(10_000));
			Tx tx = transfer(senderKey, recipient, Address.NATIVE_TOKEN, 1_000, 100);

			ExecutionResult result = processor(new TransferHandler()).executeTransactions(
					state, block(), List.of(tx), NetworkParamsStateImpl.ZERO);

			assertThat(result.getValidTxs()).containsExactly(tx);
			assertBalance(state, sender, Address.NATIVE_TOKEN, 8_900);
			assertBalance(state, recipient, Address.NATIVE_TOKEN, 1_000);
			assertBalance(state, MINER, Address.NATIVE_TOKEN, 100);
			assertThat(state.getNonce(sender).getNonce()).isZero();
			Hash root = storage.persist(state);
			WorldState reloaded = storage.reload(root, false);
			assertBalance(reloaded, sender, Address.NATIVE_TOKEN, 8_900);
			assertBalance(reloaded, recipient, Address.NATIVE_TOKEN, 1_000);
			assertBalance(reloaded, MINER, Address.NATIVE_TOKEN, 100);
		}
	}

	@Test
	void customTokenTransferUsesTokenBalanceButAlwaysPaysFeeInNativeToken() throws Exception {
		PrivateKey senderKey = key(3);
		Address sender = senderKey.getAddress();
		Address recipient = key(4).getAddress();
		Address token = Address.fromHexString("0x00000000000000000000000000000000000000aa");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = fundedState(storage, sender, Address.NATIVE_TOKEN, Wei.valueOf(1_000));
			state.setToken(token, existingToken(Wei.valueOf(5_000)));
			state.setBalance(sender, token, balance(5_000));
			Tx tx = transfer(senderKey, recipient, token, 600, 100);

			processor(new TransferHandler()).executeTransactions(state, block(), List.of(tx), NetworkParamsStateImpl.ZERO);

			assertBalance(state, sender, Address.NATIVE_TOKEN, 900);
			assertBalance(state, sender, token, 4_400);
			assertBalance(state, recipient, token, 600);
			assertBalance(state, MINER, Address.NATIVE_TOKEN, 100);
			Hash root = storage.persist(state);
			WorldState reloaded = storage.reload(root, false);
			assertBalance(reloaded, sender, Address.NATIVE_TOKEN, 900);
			assertBalance(reloaded, sender, token, 4_400);
			assertBalance(reloaded, recipient, token, 600);
		}
	}

	@Test
	void governanceCreateFeeIsDeductedByRealProcessorAndPersists() throws Exception {
		PrivateKey senderKey = key(5);
		Address sender = senderKey.getAddress();
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = fundedState(storage, sender, Address.NATIVE_TOKEN, Wei.valueOf(1_000));
			Tx governance = TxBuilder.create()
					.setNetworkParams()
					.minTxBaseFee(Wei.ZERO)
					.done()
					.network(Network.TESTNET)
					.nonce(0L)
					.fee(Wei.valueOf(100))
					.sign(senderKey);
			TxHandler noOpGovernanceHandler = new TxHandler() {
				@Override
				public TxType getSupportedType() {
					return TxType.BIP_CREATE;
				}

				@Override
				public void execute(TxExecutionContext context) {
				}
			};

			ExecutionResult result = processor(noOpGovernanceHandler).executeTransactions(
					state, block(), List.of(governance), NetworkParamsStateImpl.ZERO);

			assertThat(result.getTotalFeesCollected()).isEqualTo(Wei.valueOf(100));
			assertThat(result.getTotalSupplyIncrease()).isEqualTo(Wei.ZERO);
			assertBalance(state, sender, Address.NATIVE_TOKEN, 900);
			assertBalance(state, MINER, Address.NATIVE_TOKEN, 100);
			Hash root = storage.persist(state);
			assertBalance(storage.reload(root, false), sender, Address.NATIVE_TOKEN, 900);
		}
	}

	private WorldState fundedState(PersistentWorldStateTestSupport storage, Address sender,
			Address token, Wei balance) {
		WorldState state = storage.createEmpty(false);
		state.setToken(Address.NATIVE_TOKEN, existingToken(Wei.valueOf(1_000_000)));
		state.setBalance(sender, token, ((AccountBalanceStateImpl) AccountBalanceStateImpl.ZERO)
				.credit(balance, 0, BLOCK_TIME));
		return state;
	}

	private AccountBalanceStateImpl balance(long amount) {
		return ((AccountBalanceStateImpl) AccountBalanceStateImpl.ZERO)
				.credit(Wei.valueOf(amount), 0, BLOCK_TIME);
	}

	private TokenStateImpl existingToken(Wei totalSupply) {
		return TokenStateImpl.builder()
				.version(TokenStateVersion.getLatest())
				.name("Test")
				.smallestUnitName("TST")
				.numberOfDecimals(0)
				.maxSupply(BigInteger.valueOf(Long.MAX_VALUE))
				.userBurnable(true)
				.originTxHash(Hash.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.totalSupply(totalSupply)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(BLOCK_TIME)
				.build();
	}

	private Tx transfer(PrivateKey sender, Address recipient, Address token, long amount, long fee) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.recipient(recipient)
				.tokenAddress(token)
				.amount(Wei.valueOf(amount))
				.fee(Wei.valueOf(fee))
				.nonce(0L)
				.sign(sender);
	}

	private StateProcessor processor(TxHandler handler) {
		return new StateProcessor(List.of(handler), mock(MiningEconomicsActivationService.class),
				mock(ValidatorMiningPolicyService.class));
	}

	private SimpleBlock block() {
		return SimpleBlock.builder().height(1).timestamp(BLOCK_TIME).coinbase(MINER).build();
	}

	private PrivateKey key(int value) {
		return PrivateKey.wrap(Bytes32.leftPad(Bytes32.fromHexString(String.format("0x%02x", value))));
	}

	private void assertBalance(WorldState state, Address owner, Address token, long expected) {
		assertThat(state.getBalance(owner, token).getBalance()).isEqualTo(Wei.valueOf(expected));
	}
}
