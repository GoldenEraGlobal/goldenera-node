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
package global.goldenera.node.shared.utils;

import global.goldenera.node.shared.exceptions.GEValidationException;

public class PaginationUtil {
	public static final int MAX_PAGE_SIZE = 100;
	public static final int MAX_BLOCK_RANGE_LIMIT = 500;

	public static void validatePageRequest(int pageNumber, int pageSize) {
		if (pageNumber < 0) {
			throw new GEValidationException("Invalid page number (min 0).");
		}
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new GEValidationException(
					String.format("Invalid page size (min 1, max %d).", MAX_PAGE_SIZE));
		}
	}

	public static void validateRangeRequest(long fromHeight, long toHeight) {
		if (fromHeight < 0L || toHeight < 0L) {
			throw new GEValidationException("From height and to height must be greater than or equal to 0");
		}
		if (fromHeight > toHeight) {
			throw new GEValidationException("From height must be less than to height");
		}
		long range = (toHeight - fromHeight) + 1;
		if (range > MAX_BLOCK_RANGE_LIMIT) {
			throw new GEValidationException(
					String.format("Requested range (%d) exceeds the maximum allowed limit (%d).",
							range, MAX_BLOCK_RANGE_LIMIT));
		}
	}
}
