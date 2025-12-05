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
package global.goldenera.node.core.api.v1.mempool;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.api.v1.mempool.dtos.MempoolSubmitTxDtoV1;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@RequestMapping("/api/core/v1/mempool")
@RestController
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolApiV1 {

	MempoolManager mempoolManager;
	MempoolStore mempoolStore;
	ChainHeadStateCache chainHeadStateCache;
	MempoolProperties mempoolProperties;

	@PostMapping("submit")
	public ResponseEntity<MempoolManager.MempoolResult> submitTx(@RequestBody MempoolSubmitTxDtoV1 input) {
		Bytes rawTxDataInBytes = Bytes.fromHexString(input.getRawTxDataInHex());
		Tx tx = TxDecoder.INSTANCE.decode(rawTxDataInBytes);
		MempoolManager.MempoolResult result = mempoolManager.addTx(tx);
		return ResponseEntity.ok(result);
	}

	@GetMapping("by-hash/{hash}")
	public ResponseEntity<Tx> getMempoolTransactionByHash(@PathVariable Hash hash) {
		return ResponseEntity.ok(mempoolStore.getTxByHash(hash)
				.orElseThrow(() -> new GENotFoundException("Transaction not found")).getTx());
	}

	@GetMapping("inventory")
	public ResponseEntity<List<Hash>> getMempoolTransactionInventory() {
		return ResponseEntity.ok(mempoolStore.getAllTxHashes());
	}

	@GetMapping("size")
	public ResponseEntity<Long> getMempoolTransactionSize() {
		return ResponseEntity.ok(mempoolStore.getCount());
	}

	@GetMapping("recommended-fees")
	public ResponseEntity<RecommendedFeesDtoV1> getRecommendedFees() {
		final long AVERAGE_TX_SIZE = 150L;
		NetworkParamsState networkParams = chainHeadStateCache.getHeadState().getParams();
		Wei networkMinBaseFee = networkParams.getMinTxBaseFee();
		Wei networkMinByteFee = networkParams.getMinTxByteFee();
		Wei nodeMinFee = Wei.valueOf(mempoolProperties.getMinAcceptableFeeWei());
		Wei networkMinTotal = networkMinBaseFee.add(networkMinByteFee.multiply(AVERAGE_TX_SIZE));
		Wei effectiveMinTotal = networkMinTotal.compareTo(nodeMinFee) >= 0 ? networkMinTotal : nodeMinFee;

		Wei minBaseFee = networkMinBaseFee;
		Wei minByteFee;
		if (effectiveMinTotal.compareTo(networkMinTotal) > 0) {
			minByteFee = effectiveMinTotal.subtract(minBaseFee).divide(AVERAGE_TX_SIZE);
		} else {
			minByteFee = networkMinByteFee;
		}

		MempoolStore.FeeStatistics feeStats = mempoolStore.getFeeStatistics();

		RecommendedFeesDtoV1.FeeLevel slow = new RecommendedFeesDtoV1.FeeLevel(
				minBaseFee,
				minByteFee,
				effectiveMinTotal);

		Wei standardByteFee;
		if (feeStats.txCount() == 0 || feeStats.medianFeePerByte() <= 0) {
			standardByteFee = minByteFee.multiply(110).divide(100);
		} else {
			Wei medianFromMempool = Wei.valueOf(BigDecimal.valueOf(feeStats.medianFeePerByte())
					.setScale(0, RoundingMode.CEILING)
					.toBigInteger());
			standardByteFee = medianFromMempool.compareTo(minByteFee) >= 0 ? medianFromMempool : minByteFee;
		}
		Wei standardTotal = minBaseFee.add(standardByteFee.multiply(AVERAGE_TX_SIZE));
		RecommendedFeesDtoV1.FeeLevel standard = new RecommendedFeesDtoV1.FeeLevel(
				minBaseFee,
				standardByteFee,
				standardTotal);

		Wei fastByteFee;
		if (feeStats.txCount() == 0 || feeStats.fastFeePerByte() <= 0) {
			fastByteFee = minByteFee.multiply(125).divide(100);
		} else {
			Wei fastFromMempool = Wei.valueOf(BigDecimal.valueOf(feeStats.fastFeePerByte())
					.setScale(0, RoundingMode.CEILING)
					.toBigInteger());
			if (fastFromMempool.compareTo(standardByteFee) > 0) {
				fastByteFee = fastFromMempool;
			} else {
				fastByteFee = standardByteFee.multiply(120).divide(100);
			}
		}
		Wei fastTotal = minBaseFee.add(fastByteFee.multiply(AVERAGE_TX_SIZE));
		RecommendedFeesDtoV1.FeeLevel fast = new RecommendedFeesDtoV1.FeeLevel(
				minBaseFee,
				fastByteFee,
				fastTotal);

		RecommendedFeesDtoV1 response = new RecommendedFeesDtoV1(
				slow,
				standard,
				fast,
				mempoolStore.getCount());

		return ResponseEntity.ok(response);
	}

	@GetMapping("pending-txs/{address}")
	public ResponseEntity<List<Tx>> getPendingTransactionsByAddress(@PathVariable Address address) {
		List<Tx> pendingTxs = mempoolStore.getTxsBySender(address).stream()
				.map(entry -> entry.getTx())
				.toList();
		return ResponseEntity.ok(pendingTxs);
	}
}
