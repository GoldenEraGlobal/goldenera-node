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
package global.goldenera.node.explorer.api.v1.miningeconomics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.services.core.ExValidatorCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;

class MiningEconomicsIndexedSnapshotServiceTest {

	@Test
	void rejectsAnIndexerHeadChangeDuringValidatorRead() {
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		ExStatus before = new ExStatus(1, 42, hash(1), Instant.EPOCH, null);
		ExStatus after = new ExStatus(1, 42, hash(2), Instant.EPOCH, null);
		when(status.getStatusOrThrow()).thenReturn(before, after);
		ExValidatorCoreService validators = mock(ExValidatorCoreService.class);
		when(validators.getAll()).thenReturn(List.of());
		MiningEconomicsIndexedSnapshotService service =
				new MiningEconomicsIndexedSnapshotService(status, validators);

		assertThatThrownBy(service::capture)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index changed");
	}

	private Hash hash(int value) {
		return Hash.fromHexString(String.format("0x%064x", value));
	}
}
