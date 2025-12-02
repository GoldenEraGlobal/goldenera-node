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
package global.goldenera.node.core.storage.blockchain;

import static lombok.AccessLevel.PRIVATE;

import java.util.HashMap;
import java.util.Map;

import org.rocksdb.ColumnFamilyHandle;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Getter
@Component
public class RocksDbColumnFamilies {
	public static final byte[] KEY_LATEST_BLOCK_HASH = "LATEST_BLOCK_HASH".getBytes();

	// Core Data
	public static final String CF_BLOCKS = "blocks";
	public static final String CF_STATE_TRIE = "state_trie";

	// Indexes
	public static final String CF_TX_INDEX = "tx_index";
	public static final String CF_HASH_BY_HEIGHT = "hash_by_height";

	// Metadata
	public static final String CF_METADATA = "metadata";

	Map<String, ColumnFamilyHandle> handles = new HashMap<>();

	public void addHandle(String name, ColumnFamilyHandle handle) {
		handles.put(name, handle);
	}

	public ColumnFamilyHandle blocks() {
		return handles.get(CF_BLOCKS);
	}

	public ColumnFamilyHandle stateTrie() {
		return handles.get(CF_STATE_TRIE);
	}

	public ColumnFamilyHandle txIndex() {
		return handles.get(CF_TX_INDEX);
	}

	public ColumnFamilyHandle hashByHeight() {
		return handles.get(CF_HASH_BY_HEIGHT);
	}

	public ColumnFamilyHandle metadata() {
		return handles.get(CF_METADATA);
	}
}
