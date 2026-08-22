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
package global.goldenera.node.explorer.snapshot;

public record ExplorerSnapshotBinding(
		int carrierNetworkCode,
		String chainId,
		String genesisHash,
		long checkpointHeight,
		String checkpointHash,
		String checkpointStateRoot,
		String coreStateSigningHash,
		String coreArchiveSigningHash) {

	public ExplorerSnapshotBinding {
		if (carrierNetworkCode < 0 || carrierNetworkCode > 255 || chainId == null || chainId.isBlank()
				|| checkpointHeight < 0) {
			throw new IllegalArgumentException("Invalid explorer snapshot chain binding");
		}
		requireHash(genesisHash, true, "genesis hash");
		requireHash(checkpointHash, true, "checkpoint hash");
		requireHash(checkpointStateRoot, true, "checkpoint state root");
		requireHash(coreStateSigningHash, false, "core state signing hash");
		requireHash(coreArchiveSigningHash, false, "core archive signing hash");
	}

	private static void requireHash(String value, boolean prefixed, String label) {
		String pattern = prefixed ? "0x[0-9a-f]{64}" : "[0-9a-f]{64}";
		if (value == null || !value.matches(pattern)) {
			throw new IllegalArgumentException("Invalid explorer snapshot " + label);
		}
	}
}
