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
package global.goldenera.node.core.sync.snapshot.archive;

import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

public final class CoreSnapshotArchiveLimits {

	public static final int FORMAT_VERSION = SnapshotFormatCompatibility.CURRENT_ARCHIVE_FORMAT;
	public static final int MAX_CHUNK_COUNT = 16_384;
	public static final int MAX_BLOCKS_PER_CHUNK = 4_096;
	public static final int MAX_ENCODED_BLOCK_BYTES = 48 * 1024 * 1024;
	public static final long MAX_CHUNK_BYTES = 256L * 1024 * 1024;
	public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024 * 1024;
	public static final long MAX_TOTAL_BLOCKS = (long) MAX_CHUNK_COUNT * MAX_BLOCKS_PER_CHUNK;

	private CoreSnapshotArchiveLimits() {
	}
}
