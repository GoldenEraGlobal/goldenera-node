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
package global.goldenera.node.core.sync.snapshot.operator;

import org.springframework.beans.BeanUtils;

import global.goldenera.node.core.properties.BlockchainDbProperties;

final class SnapshotCloneResourceLimits {

	static final int ROCKS_DB_BLOCK_CACHE_MB = 64;
	static final int ROCKS_DB_WRITE_BUFFER_MB = 8;
	static final int ROCKS_DB_MAX_WRITE_BUFFERS = 2;
	static final int BLOCK_CACHE_MB = 32;
	static final int TRIE_NODE_CACHE_MB = 32;
	static final int TX_CACHE_MB = 8;
	static final int INDEX_CACHE_ENTRIES = 4_096;

	private SnapshotCloneResourceLimits() {
	}

	static BlockchainDbProperties databaseProperties(BlockchainDbProperties source) {
		BlockchainDbProperties clone = new BlockchainDbProperties();
		BeanUtils.copyProperties(source, clone);
		clone.setRocksdbBlockCacheMb(ROCKS_DB_BLOCK_CACHE_MB);
		clone.setRocksdbWriteBufferMb(ROCKS_DB_WRITE_BUFFER_MB);
		clone.setRocksdbMaxWriteBuffers(ROCKS_DB_MAX_WRITE_BUFFERS);
		return clone;
	}
}
