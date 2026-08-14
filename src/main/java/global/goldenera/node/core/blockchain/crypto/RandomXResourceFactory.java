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

import java.util.Set;

import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXVM;

interface RandomXResourceFactory {

	RandomXCache createCache(Set<RandomXFlag> flags);

	RandomXDataset createDataset(Set<RandomXFlag> flags);

	RandomXVM createVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset);
}

final class NativeRandomXResourceFactory implements RandomXResourceFactory {

	@Override
	public RandomXCache createCache(Set<RandomXFlag> flags) {
		return new RandomXCache(flags);
	}

	@Override
	public RandomXDataset createDataset(Set<RandomXFlag> flags) {
		return new RandomXDataset(flags);
	}

	@Override
	public RandomXVM createVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
		return new RandomXVM(flags, cache, dataset);
	}
}
