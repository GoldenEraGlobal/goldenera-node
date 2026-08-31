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
package global.goldenera.node.explorer.api.v1.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.shared.exceptions.GEValidationException;

class BulkPageRequestValidationTest {
	@Test
	void acceptsTheMaximumNumberOfFilterValues() {
		assertThatCode(() -> txRequest(addresses(BulkPageRequestValidator.MAX_FILTER_VALUES)))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsOversizedFilterSetsDuringDtoConstruction() {
		assertThatThrownBy(() -> txRequest(addresses(BulkPageRequestValidator.MAX_FILTER_VALUES + 1)))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("100");
	}

	@Test
	void rejectsDeepOffsetsDuringDtoConstruction() {
		assertThatThrownBy(() -> new BulkTxPageRequestV1(
				1_001, 100, null, null, null, null, null, null, null,
				null, null, null, null))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("offset");
	}

	private BulkTxPageRequestV1 txRequest(Set<Address> addresses) {
		return new BulkTxPageRequestV1(
				0, 25, null, addresses, null, null, null, null, null,
				null, null, null, null);
	}

	private Set<Address> addresses(int count) {
		Set<Address> addresses = new LinkedHashSet<>();
		for (int value = 1; value <= count; value++) {
			addresses.add(Address.fromHexString(String.format("0x%040x", value)));
		}
		return addresses;
	}
}
