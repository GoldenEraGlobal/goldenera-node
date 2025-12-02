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
package global.goldenera.node.core.api.v1.info;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.Constants;
import global.goldenera.node.core.api.v1.info.dtos.NodeInfoDtoV1;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@RequestMapping("/api/core/v1/info")
@RestController
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class NodeInfoApiV1 {

	ChainQuery chainQueryService;
	IdentityService identityService;

	@GetMapping
	public ResponseEntity<NodeInfoDtoV1> getNodeInfo() {
		Address identity = identityService.getNodeIdentityAddress();
		Optional<StoredBlock> storedBlock = chainQueryService.getLatestStoredBlock();
		BlockHeader blockHeader = storedBlock.map(StoredBlock::getBlock).map(Block::getHeader).orElse(null);
		BigInteger cumulativeDifficulty = storedBlock.map(StoredBlock::getCumulativeDifficulty).orElse(null);

		return ResponseEntity.ok(NodeInfoDtoV1.builder()
				.version(Constants.NODE_VERSION)
				.identity(identity)
				.blockHeader(blockHeader)
				.timestamp(Instant.now())
				.cumulativeDifficulty(cumulativeDifficulty)
				.build());
	}
}
