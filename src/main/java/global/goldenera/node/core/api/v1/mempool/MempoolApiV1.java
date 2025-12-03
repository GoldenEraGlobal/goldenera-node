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

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.api.v1.mempool.dtos.MempoolSubmitTxDtoV1;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolStore;
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
}
