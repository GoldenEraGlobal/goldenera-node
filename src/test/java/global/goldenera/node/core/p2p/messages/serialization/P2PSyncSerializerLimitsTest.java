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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersReqDto;

class P2PSyncSerializerLimitsTest {

	@Test
	void locatorCountBombIsRejectedDuringListDecode() {
		P2PBlockHeadersReqDto oversized = P2PBlockHeadersReqDto.builder()
				.locators(Collections.nCopies(P2PSyncSerializer.MAX_LOCATORS + 1, Hash.ZERO))
				.stopHash(Hash.ZERO)
				.batchSize(1)
				.build();

		assertThatThrownBy(() -> P2PSyncSerializer.decodeGetHeaders(
				P2PSyncSerializer.encodeGetHeaders(oversized)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maximum");
	}
}
