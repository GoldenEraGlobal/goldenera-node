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
package global.goldenera.node.core.p2p.messages.serialization;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.serialization.block.BlockDecoder;
import global.goldenera.cryptoj.serialization.block.BlockEncoder;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockDto;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;
import lombok.experimental.UtilityClass;

@UtilityClass
public class P2PBlockSerializer {

	public static Bytes encodeBlock(P2PBlockDto dto) {
		return RLP.encode((out) -> {
			out.startList();
			out.writeRaw(BlockEncoder.INSTANCE.encode(dto.getBlock(), true));
			out.endList();
		});
	}

	public static P2PBlockDto decodeBlock(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		Block block = BlockDecoder.INSTANCE.decode(input.readRaw());
		input.leaveList();
		return P2PBlockDto.builder().block(block).build();
	}

}
