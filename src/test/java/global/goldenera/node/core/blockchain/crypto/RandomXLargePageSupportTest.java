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
package global.goldenera.node.core.blockchain.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RandomXLargePageSupportTest {

	@TempDir
	Path tempDirectory;

	@Test
	void acceptsLinuxPoolLargeEnoughForTheFullWorkingSet() throws IOException {
		Path meminfo = writeMeminfo(1280, 1280, 2048);

		var availability = RandomXLargePageSupport.detect(meminfo, "Linux", true);

		assertThat(availability.available()).isTrue();
	}

	@Test
	void rejectsInsufficientFreeLinuxPoolBeforeNativeAllocation() throws IOException {
		Path meminfo = writeMeminfo(1280, 1150, 2048);

		var availability = RandomXLargePageSupport.detect(meminfo, "Linux", true);

		assertThat(availability.available()).isFalse();
		assertThat(availability.reason()).contains("2300 MB").contains("2560 MB required");
	}

	@Test
	void containerPreflightCanDisableLargePagesExplicitly() throws IOException {
		Path meminfo = writeMeminfo(1280, 1280, 2048);

		var availability = RandomXLargePageSupport.detect(meminfo, "Linux", false);

		assertThat(availability.available()).isFalse();
		assertThat(availability.reason()).contains("container preflight");
	}

	private Path writeMeminfo(long totalPages, long freePages, long pageSizeKb) throws IOException {
		Path meminfo = tempDirectory.resolve("meminfo");
		Files.writeString(meminfo, "HugePages_Total: " + totalPages + "\n"
				+ "HugePages_Free: " + freePages + "\n"
				+ "Hugepagesize: " + pageSizeKb + " kB\n");
		return meminfo;
	}
}
