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
package global.goldenera.node.core.blockchain.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class ChainHeadStateCacheTest {

	@Test
	@Timeout(5)
	void blockEventOnlyInvalidatesAndConcurrentReadersPerformOneLazyReload() throws Exception {
		Hash oldRoot = hash(1);
		Hash newRoot = hash(2);
		WorldState oldState = state(oldRoot);
		WorldState newState = state(newRoot);
		WorldStateFactory factory = mock(WorldStateFactory.class);
		when(factory.createForValidation(oldRoot)).thenReturn(oldState);

		CountDownLatch newLoadStarted = new CountDownLatch(1);
		CountDownLatch allowNewLoad = new CountDownLatch(1);
		when(factory.createForValidation(newRoot)).thenAnswer(invocation -> {
			newLoadStarted.countDown();
			assertThat(allowNewLoad.await(2, TimeUnit.SECONDS)).isTrue();
			return newState;
		});

		AtomicReference<StoredBlock> latest = new AtomicReference<>(storedBlock(oldRoot));
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(invocation -> latest.get());
		ChainHeadStateCache cache = new ChainHeadStateCache(factory, chainQuery);
		cache.init();

		latest.set(storedBlock(newRoot));
		cache.onBlockConnected(mock(BlockConnectedEvent.class));

		verify(factory, times(1)).createForValidation(oldRoot);
		verify(factory, times(0)).createForValidation(newRoot);

		ExecutorService readers = Executors.newFixedThreadPool(8);
		try {
			List<Future<WorldState>> results = new ArrayList<>();
			for (int index = 0; index < 8; index++) {
				results.add(readers.submit(cache::getHeadState));
			}
			assertThat(newLoadStarted.await(1, TimeUnit.SECONDS)).isTrue();
			allowNewLoad.countDown();
			for (Future<WorldState> result : results) {
				assertThat(result.get(1, TimeUnit.SECONDS)).isSameAs(newState);
			}
		} finally {
			allowNewLoad.countDown();
			readers.shutdownNow();
		}

		verify(factory, times(1)).createForValidation(newRoot);
	}

	private WorldState state(Hash root) {
		WorldState state = mock(WorldState.class);
		when(state.getFinalStateRoot()).thenReturn(root);
		return state;
	}

	private StoredBlock storedBlock(Hash root) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getStateRootHash()).thenReturn(root);
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getBlock()).thenReturn(block);
		return storedBlock;
	}

	private Hash hash(int value) {
		return Hash.fromHexString(String.format("0x%064x", value));
	}
}
