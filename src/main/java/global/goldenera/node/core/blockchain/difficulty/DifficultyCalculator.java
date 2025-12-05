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
package global.goldenera.node.core.blockchain.difficulty;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class DifficultyCalculator {

	ChainQuery chainQueryService;

	/**
	 * Calculates the difficulty for the next block (at parentBlock.height + 1)
	 * using Absolute ASERT.
	 */
	public BigInteger calculateNextDifficulty(
			@NonNull BlockHeader parentBlock,
			@NonNull NetworkParamsState params) {

		long nextHeight = parentBlock.getHeight() + 1;

		if (nextHeight <= 1) {
			log.debug("Bootstrap mode for block {}: Using minDifficulty={}", nextHeight, params.getMinDifficulty());
			return params.getMinDifficulty();
		}

		try {
			final long anchorHeight = params.getAsertAnchorHeight();
			final Block anchorBlock = chainQueryService.getStoredBlockByHeight(anchorHeight)
					.map(sb -> sb.getBlock())
					.orElseThrow(() -> new GENotFoundException("Anchor block " + anchorHeight + " not found"));

			long timeDelta = parentBlock.getTimestamp().toEpochMilli()
					- anchorBlock.getHeader().getTimestamp().toEpochMilli();
			long heightDelta = parentBlock.getHeight() - anchorBlock.getHeight();
			long targetTimeMs = params.getTargetMiningTimeMs();
			long tauMs = params.getAsertHalfLifeBlocks() * targetTimeMs;

			BigInteger newDifficulty = DifficultyUtil.calculateAbsoluteAsertDifficulty(
					anchorBlock.getHeader().getDifficulty(),
					timeDelta,
					heightDelta,
					targetTimeMs,
					tauMs,
					params.getMinDifficulty());

			log.debug("ASERT: H={} (Delta {}), TimeDelta={}ms, NewDiff={}",
					nextHeight, heightDelta, timeDelta, newDifficulty);

			return newDifficulty;
		} catch (Exception e) {
			log.error("Failed to calculate ASERT difficulty. Fallback to parent.", e);
			return parentBlock.getDifficulty();
		}
	}
}