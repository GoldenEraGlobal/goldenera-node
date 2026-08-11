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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class MempoolBenchmark {
	private static final int BATCH_SIZE = 256;

	@Benchmark
	@OperationsPerInvocation(BATCH_SIZE)
	public long batchAdmission(AdmissionState state) {
		state.store.addTransactions(state.entries, Map.of(state.sender, 0L), MempoolTxAddEvent.AddReason.SYNC);
		return state.store.getCount();
	}

	@Benchmark
	@OperationsPerInvocation(BATCH_SIZE)
	public long multiSenderBatchAdmission(MultiSenderAdmissionState state) {
		state.store.addTransactions(state.entries, state.chainNonces, MempoolTxAddEvent.AddReason.SYNC);
		return state.store.getCount();
	}

	@Benchmark
	public Object singleAdmission(SingleAdmissionState state) {
		return state.store.addTransaction(state.entry, 0, MempoolTxAddEvent.AddReason.NEW);
	}

	@Benchmark
	public Object replaceByFee(ReplacementState state) {
		return state.store.addTransaction(state.replacement, 0, MempoolTxAddEvent.AddReason.NEW);
	}

	@Benchmark
	public long revalidationStoragePass(RevalidationState state) {
		state.store.resynchronizeSenders(Map.of(state.sender, 0L));
		state.store.reconcileSenderBalances(Map.of(state.sender,
				new MempoolStore.SenderBalances(Wei.valueOf(Long.MAX_VALUE), Map.of())));
		return state.store.getCount();
	}

	@State(Scope.Thread)
	public static class AdmissionState extends BaseState {
		List<MempoolEntry> entries;

		@Setup(Level.Trial)
		public void createEntries() throws Exception {
			initializeIdentity();
			entries = entries(BATCH_SIZE, 100);
		}

		@Setup(Level.Invocation)
		public void createStore() {
			store = store();
		}
	}

	@State(Scope.Thread)
	public static class SingleAdmissionState extends BaseState {
		MempoolEntry entry;

		@Setup(Level.Trial)
		public void createEntry() throws Exception {
			initializeIdentity();
			entry = entry(1, 100);
		}

		@Setup(Level.Invocation)
		public void createStore() {
			store = store();
		}
	}

	@State(Scope.Thread)
	public static class MultiSenderAdmissionState extends BaseState {
		List<MempoolEntry> entries;
		Map<Address, Long> chainNonces;

		@Setup(Level.Trial)
		public void createEntries() throws Exception {
			entries = new ArrayList<>(BATCH_SIZE);
			chainNonces = new HashMap<>();
			for (int id = 1; id <= BATCH_SIZE; id++) {
				PrivateKey senderKey = PrivateKey.wrap(
						Bytes32.fromHexString(String.format("0x%064x", id)));
				Tx tx = TxBuilder.create().type(TxType.TRANSFER).network(Network.TESTNET)
						.recipient(Address.ZERO).amount(Wei.valueOf(1)).fee(Wei.valueOf(100)).nonce(1)
						.sign(senderKey);
				entries.add(new MempoolEntry(tx));
				chainNonces.put(senderKey.getAddress(), 0L);
			}
		}

		@Setup(Level.Invocation)
		public void createStore() {
			store = store();
		}
	}

	@State(Scope.Thread)
	public static class ReplacementState extends BaseState {
		MempoolEntry original;
		MempoolEntry replacement;

		@Setup(Level.Trial)
		public void createEntries() throws Exception {
			initializeIdentity();
			original = entry(1, 100);
			replacement = entry(1, 110);
		}

		@Setup(Level.Invocation)
		public void createStore() {
			store = store();
			store.addTransaction(original, 0, MempoolTxAddEvent.AddReason.NEW);
		}
	}

	@State(Scope.Thread)
	public static class RevalidationState extends BaseState {
		List<MempoolEntry> entries;

		@Setup(Level.Trial)
		public void createEntries() throws Exception {
			initializeIdentity();
			entries = entries(256, 100);
		}

		@Setup(Level.Invocation)
		public void createStore() {
			store = store();
			store.addTransactions(entries, Map.of(sender, 0L), MempoolTxAddEvent.AddReason.SYNC);
		}
	}

	public abstract static class BaseState {
		MempoolStore store;
		PrivateKey key;
		Address sender;

		void initializeIdentity() {
			key = PrivateKey.wrap(Bytes32.leftPad(Bytes32.fromHexString("0x01")));
			sender = key.getAddress();
		}

		List<MempoolEntry> entries(int count, long fee) throws Exception {
			List<MempoolEntry> result = new ArrayList<>(count);
			for (int nonce = 1; nonce <= count; nonce++) {
				result.add(entry(nonce, fee));
			}
			return result;
		}

		MempoolEntry entry(long nonce, long fee) throws Exception {
			Tx tx = TxBuilder.create().type(TxType.TRANSFER).network(Network.TESTNET)
					.recipient(Address.ZERO).amount(Wei.valueOf(1)).fee(Wei.valueOf(fee)).nonce(nonce).sign(key);
			return new MempoolEntry(tx);
		}

		MempoolStore store() {
			MempoolProperties properties = new MempoolProperties();
			properties.setMaxSize(1_000L);
			properties.setMaxNonceGap(1_000L);
			properties.setMinAcceptableFeeWei(BigInteger.ZERO);
			properties.setTxExpireTimeInMinutes(60);
			return new MempoolStore(new SimpleMeterRegistry(), properties, null, event -> { });
		}
	}
}
