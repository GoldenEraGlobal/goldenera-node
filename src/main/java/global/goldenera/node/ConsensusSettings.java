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
package global.goldenera.node;

import java.util.Map;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants.ForkName;

/**
 * Consensus-critical settings that are hardcoded in Constants.
 * 
 * These settings MUST be identical across all nodes on the network.
 * They should NOT be customizable by developers locally as that would
 * break consensus.
 * 
 * Includes:
 * - Fork activation heights
 * - Block checkpoints (verified block hashes)
 * - Parameter overrides at specific block heights
 */
public record ConsensusSettings(
        // Fork activation blocks - when each hard fork activates
        Map<ForkName, Long> forkActivationBlocks,

        // Block checkpoints - verified block hashes for quick validation
        Map<Long, Hash> blockCheckpoints,

        // Parameter overrides at specific block heights
        Map<Long, Long> maxBlockSizeOverrides,
        Map<Long, Long> maxTxSizeOverrides,
        Map<Long, Long> maxTxCountOverrides,
        Map<Long, Long> maxHeaderSizeOverrides) {

    /**
     * Create empty consensus settings (for networks with no forks yet).
     */
    public static ConsensusSettings empty() {
        return new ConsensusSettings(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }
}
