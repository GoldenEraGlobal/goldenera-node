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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import global.goldenera.node.shared.exceptions.GEValidationException;

class PaginationUtilTest {
	@Test
	void acceptsTheMaximumOffset() {
		PaginationUtil.validatePageRequest(1_000, 100);
	}

	@Test
	void rejectsOffsetsBeyondTheMaximum() {
		assertThatThrownBy(() -> PaginationUtil.validatePageRequest(1_001, 100))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("offset");
	}

	@Test
	void buildsAStableSortWithTheRequestedDirection() {
		Sort sort = PaginationUtil.stableSort(Sort.Direction.DESC, "timestamp", "id");

		assertThat(sort.stream().map(Sort.Order::getProperty)).containsExactly("timestamp", "id");
		assertThat(sort.stream().map(Sort.Order::getDirection))
				.containsExactly(Sort.Direction.DESC, Sort.Direction.DESC);
	}
}
