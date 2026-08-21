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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;

public record SnapshotChunkDescriptor(
		int index,
		String id,
		String url,
		int nodeCount,
		long byteCount,
		Hash contentHash) {

	public SnapshotChunkDescriptor {
		Objects.requireNonNull(id, "id");
		if (id.isBlank() || id.getBytes(StandardCharsets.UTF_8).length > 128
				|| id.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Chunk ID must be non-blank, control-free and at most 128 UTF-8 bytes");
		}
		Objects.requireNonNull(url, "url");
		if (url.getBytes(StandardCharsets.UTF_8).length > 2_048) {
			throw new IllegalArgumentException("Chunk URL exceeds 2048 UTF-8 bytes");
		}
		try {
			URI uri = new URI(url);
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
					|| !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
				throw new IllegalArgumentException("Chunk URL must be an absolute HTTP(S) URL without userinfo or fragment");
			}
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Chunk URL is invalid", e);
		}
		Objects.requireNonNull(contentHash, "contentHash");
	}
}
