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
package global.goldenera.node.core.state.serialization.bip;

import java.util.EnumMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.state.serialization.bip.impl.BipStateV1EncodingStrategy;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.rlp.RLP;

public class BipStateEncoder {

	public static final BipStateEncoder INSTANCE = new BipStateEncoder();
	private final Map<BipStateVersion, BipStateEncodingStrategy> strategies = new EnumMap<>(
			BipStateVersion.class);

	private BipStateEncoder() {
		strategies.put(BipStateVersion.V1, new BipStateV1EncodingStrategy());
	}

	public Bytes encode(BipState state) {
		BipStateVersion version = state.getVersion();
		if (version == null) {
			throw new GEFailedException("Unsupported BipState Version: " + version);
		}
		BipStateEncodingStrategy strategy = strategies.get(version);
		if (strategy == null) {
			throw new GEFailedException("Unsupported BipState Version: " + version);
		}
		return RLP.encode(out -> {
			out.startList();
			// Write version
			out.writeIntScalar(version.getCode());
			// Delegate to strategy
			strategy.encode(out, state);
			out.endList();
		});
	}

}
