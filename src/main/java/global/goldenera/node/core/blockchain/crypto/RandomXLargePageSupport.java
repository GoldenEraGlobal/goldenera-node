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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class RandomXLargePageSupport {

	static final String ENABLED_PROPERTY = "goldenera.randomx.large-pages-enabled";
	static final long MINIMUM_FREE_MEMORY_MB = 2560L;

	private static final Path LINUX_MEMINFO = Path.of("/proc/meminfo");

	Availability currentAvailability() {
		boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
		return detect(LINUX_MEMINFO, System.getProperty("os.name", ""), enabled);
	}

	static Availability detect(Path meminfoPath, String osName, boolean enabled) {
		if (!enabled) {
			return new Availability(false, "disabled by container preflight");
		}
		String normalizedOs = osName.toLowerCase(Locale.ROOT);
		if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
			return new Availability(false, "unsupported on macOS");
		}
		if (!normalizedOs.contains("linux")) {
			return new Availability(true, "enabled for this operating system");
		}

		try {
			long freePages = readMeminfoValue(meminfoPath, "HugePages_Free");
			long pageSizeKb = readMeminfoValue(meminfoPath, "Hugepagesize");
			long freeMemoryMb = Math.multiplyExact(freePages, pageSizeKb) / 1024L;
			if (freeMemoryMb < MINIMUM_FREE_MEMORY_MB) {
				return new Availability(false, "only " + freeMemoryMb + " MB of huge pages are free; "
						+ MINIMUM_FREE_MEMORY_MB + " MB required");
			}
			return new Availability(true, freeMemoryMb + " MB of huge pages are free");
		} catch (IOException | ArithmeticException | IllegalArgumentException e) {
			return new Availability(false, "unable to inspect Linux huge-page availability: " + e.getMessage());
		}
	}

	private static long readMeminfoValue(Path meminfoPath, String key) throws IOException {
		String prefix = key + ":";
		return Files.readAllLines(meminfoPath).stream()
				.map(String::trim)
				.filter(line -> line.startsWith(prefix))
				.map(line -> line.substring(prefix.length()).trim().split("\\s+")[0])
				.mapToLong(Long::parseLong)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(key + " is missing from " + meminfoPath));
	}

	record Availability(boolean available, String reason) {
	}
}
