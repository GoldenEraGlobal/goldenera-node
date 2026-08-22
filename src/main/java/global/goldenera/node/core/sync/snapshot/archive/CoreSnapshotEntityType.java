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

import global.goldenera.node.core.state.WorldStateFactory;

import org.apache.tuweni.bytes.Bytes;

/** Materialized core index whose contents are committed by a checkpoint state trie. */
public enum CoreSnapshotEntityType {
	TOKEN(1, WorldStateFactory.KEY_TOKEN),
	AUTHORITY(2, WorldStateFactory.KEY_AUTHORITY),
	VALIDATOR(3, WorldStateFactory.KEY_VALIDATOR);

	private final int code;
	private final Bytes worldStateKey;

	CoreSnapshotEntityType(int code, Bytes worldStateKey) {
		this.code = code;
		this.worldStateKey = worldStateKey;
	}

	public int code() {
		return code;
	}

	public Bytes worldStateKey() {
		return worldStateKey;
	}

	public static CoreSnapshotEntityType fromCode(int code) {
		for (CoreSnapshotEntityType type : values()) {
			if (type.code == code) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown core snapshot entity type: " + code);
	}
}
