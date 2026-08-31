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
package global.goldenera.node.core.sync.snapshot;

import java.util.Set;

import global.goldenera.cryptoj.datatypes.Hash;

/**
 * Node-version-independent snapshot wire/storage format contract. Producers emit
 * only the current version; readers retain every explicitly listed historical
 * version until a deliberate migration removes it.
 */
public final class SnapshotFormatCompatibility {
	public static final String VERSION_NAME_PATTERN =
			"snapshot-(0|[1-9][0-9]{0,18})-[0-9a-f]{64}(?:-s[0-9]+-a[0-9]+-e[0-9]+-x[0-9]+)?";

	public static final int CURRENT_STATE_FORMAT = 1;
	public static final int CURRENT_ARCHIVE_FORMAT = 2;
	public static final int CURRENT_ENTITY_FORMAT = 1;
	public static final int CURRENT_EXPLORER_FORMAT = 1;

	public static final Set<Integer> SUPPORTED_STATE_READER_FORMATS = Set.of(1);
	public static final Set<Integer> SUPPORTED_ARCHIVE_READER_FORMATS = Set.of(2);
	public static final Set<Integer> SUPPORTED_ENTITY_READER_FORMATS = Set.of(1);
	public static final Set<Integer> SUPPORTED_EXPLORER_READER_FORMATS = Set.of(1);

	static {
		requireCurrentSupported("state", CURRENT_STATE_FORMAT, SUPPORTED_STATE_READER_FORMATS);
		requireCurrentSupported("archive", CURRENT_ARCHIVE_FORMAT, SUPPORTED_ARCHIVE_READER_FORMATS);
		requireCurrentSupported("entity", CURRENT_ENTITY_FORMAT, SUPPORTED_ENTITY_READER_FORMATS);
		requireCurrentSupported("explorer", CURRENT_EXPLORER_FORMAT, SUPPORTED_EXPLORER_READER_FORMATS);
	}

	private SnapshotFormatCompatibility() {
	}

	public static boolean supportsState(int version) {
		return SUPPORTED_STATE_READER_FORMATS.contains(version);
	}

	public static boolean supportsArchive(int version) {
		return SUPPORTED_ARCHIVE_READER_FORMATS.contains(version);
	}

	public static boolean supportsEntity(int version) {
		return SUPPORTED_ENTITY_READER_FORMATS.contains(version);
	}

	public static boolean supportsExplorer(int version) {
		return SUPPORTED_EXPLORER_READER_FORMATS.contains(version);
	}

	public static boolean supportsBlockChunkForArchive(int archiveVersion, int chunkVersion) {
		return archiveVersion == 2 && chunkVersion == 2;
	}

	public static boolean supportsEntityChunkForArchive(int archiveVersion, int chunkVersion) {
		return archiveVersion == 2 && chunkVersion == 1;
	}

	public static boolean supportsExplorerChunkForManifest(int manifestVersion, int chunkVersion) {
		return manifestVersion == 1 && chunkVersion == 1;
	}

	public static String currentVersionName(long height, Hash checkpointHash) {
		String hash = checkpointHash.toHexString().replaceFirst("^0x", "");
		return "snapshot-" + height + "-" + hash
				+ "-s" + CURRENT_STATE_FORMAT
				+ "-a" + CURRENT_ARCHIVE_FORMAT
				+ "-e" + CURRENT_ENTITY_FORMAT
				+ "-x" + CURRENT_EXPLORER_FORMAT;
	}

	private static void requireCurrentSupported(String format, int current, Set<Integer> supported) {
		if (!supported.contains(current)) {
			throw new ExceptionInInitializerError(
					"Current " + format + " snapshot format must remain readable: " + current);
		}
	}
}
