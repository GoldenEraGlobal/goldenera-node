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
package global.goldenera.node.core.state.serialization.networkparams;

import java.util.EnumMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.state.serialization.networkparams.impl.NetworkParamsStateV1EncodingStrategy;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.rlp.RLP;

public class NetworkParamsStateEncoder {

	public static final NetworkParamsStateEncoder INSTANCE = new NetworkParamsStateEncoder();
	private final Map<NetworkParamsStateVersion, NetworkParamsStateEncodingStrategy> strategies = new EnumMap<>(
			NetworkParamsStateVersion.class);

	private NetworkParamsStateEncoder() {
		strategies.put(NetworkParamsStateVersion.V1, new NetworkParamsStateV1EncodingStrategy());
	}

	public Bytes encode(NetworkParamsState state) {
		NetworkParamsStateVersion version = state.getVersion();
		if (version == null) {
			throw new GEFailedException("NetworkParamsState Version is null");
		}
		NetworkParamsStateEncodingStrategy strategy = strategies.get(version);
		if (strategy == null) {
			throw new GEFailedException("Unsupported NetworkParamsState Version: " + version);
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
