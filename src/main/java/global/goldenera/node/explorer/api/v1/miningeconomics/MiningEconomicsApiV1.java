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
package global.goldenera.node.explorer.api.v1.miningeconomics;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.explorer.api.v1.miningeconomics.dtos.MiningEconomicsSnapshotDtoV1;
import global.goldenera.node.explorer.api.v1.networkparams.mappers.NetworkParamsMapper;
import global.goldenera.node.explorer.api.v1.validator.dtos.ValidatorDtoV1;
import global.goldenera.node.explorer.api.v1.validator.mappers.ValidatorMapper;
import global.goldenera.node.explorer.api.v1.miningeconomics.MiningEconomicsIndexedSnapshotService.IndexedValidatorSnapshot;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.ExplorerApiSecurity;
import lombok.RequiredArgsConstructor;

@RestController("explorerMiningEconomicsApiV1")
@RequiredArgsConstructor
@ExplorerApiSecurity(ApiKeyPermission.READ_VALIDATOR)
@RequestMapping("/api/explorer/v1/mining-economics")
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class MiningEconomicsApiV1 {
	private static final int MAX_SNAPSHOT_ATTEMPTS = 3;

	private final ChainHeadStateCache chainHeadStateCache;
	private final MiningEconomicsIndexedSnapshotService indexedSnapshotService;
	private final NetworkParamsMapper networkParamsMapper;
	private final ValidatorMapper validatorMapper;

	@GetMapping("snapshot")
	public MiningEconomicsSnapshotDtoV1 snapshot() {
		for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
			HeadStateSnapshot before = chainHeadStateCache.getHeadSnapshot();
			IndexedValidatorSnapshot indexed;
			try {
				indexed = indexedSnapshotService.capture();
			} catch (RuntimeException e) {
				continue;
			}
			HeadStateSnapshot after = chainHeadStateCache.getHeadSnapshot();
			if (!sameHead(before, after) || !indexed.matches(before.head())) {
				continue;
			}
			List<ValidatorDtoV1> validators = validatorMapper.map(indexed.validators(), before.state());
			return new MiningEconomicsSnapshotDtoV1(
					before.head().getHeight(),
					before.head().getHash(),
					Instant.now(),
					networkParamsMapper.map(before.state().getParams()),
					validators);
		}
		throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				"Explorer validator index is not synchronized with the canonical head");
	}

	private boolean sameHead(HeadStateSnapshot first, HeadStateSnapshot second) {
		return first.head().getHeight() == second.head().getHeight()
				&& first.head().getHash().equals(second.head().getHash());
	}
}
