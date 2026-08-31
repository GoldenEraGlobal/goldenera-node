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
package global.goldenera.node.core.p2p.netty.protocol;

import lombok.experimental.UtilityClass;

/** Wire-compatible limits negotiated for block synchronization. */
@UtilityClass
public class P2PSyncProtocol {

	public static final String BLOCK_SYNC_V2_CAPABILITY = "block-sync-v2";
	public static final int LEGACY_HEADER_PAGE_LIMIT = 1_000;
	public static final int V2_HEADER_PAGE_LIMIT = 4_096;
	public static final int INTERNAL_VALIDATION_WINDOW_HEADERS = LEGACY_HEADER_PAGE_LIMIT;
	public static final int MAX_NEGOTIATED_HEADER_PAGE = V2_HEADER_PAGE_LIMIT;
	public static final int MAX_LOCAL_HEADER_WINDOW = V2_HEADER_PAGE_LIMIT;
}
