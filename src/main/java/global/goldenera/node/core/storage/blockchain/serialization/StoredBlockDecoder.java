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
package global.goldenera.node.core.storage.blockchain.serialization;

import java.util.EnumMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.impl.decoding.StoredBlockV1DecodingStrategy;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;

public class StoredBlockDecoder {

	public static final StoredBlockDecoder INSTANCE = new StoredBlockDecoder();
	private final Map<StoredBlockVersion, StoredBlockDecodingStrategy> strategies = new EnumMap<>(
			StoredBlockVersion.class);

	private StoredBlockDecoder() {
		strategies.put(StoredBlockVersion.V1, new StoredBlockV1DecodingStrategy());
	}

	public StoredBlock decode(Bytes rlpBytes) {
		if (rlpBytes == null || rlpBytes.isEmpty()) {
			throw new IllegalArgumentException("Cannot decode empty bytes");
		}
		RLP.validate(rlpBytes);
		RLPInput input = RLP.input(rlpBytes);
		input.enterList();

		// Read version
		long versionCode = input.readLongScalar();
		StoredBlockVersion version = StoredBlockVersion.fromCode(versionCode);

		StoredBlockDecodingStrategy strategy = strategies.get(version);
		if (strategy == null) {
			throw new GEFailedException("Unknown StoredBlock version: " + version);
		}

		StoredBlock storedBlock = strategy.decode(input);
		input.leaveList();
		return storedBlock.toBuilder().size(rlpBytes.size()).build();
	}
}
