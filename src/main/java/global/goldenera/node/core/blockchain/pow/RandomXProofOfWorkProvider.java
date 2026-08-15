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
package global.goldenera.node.core.blockchain.pow;

import java.util.Optional;
import java.util.function.Function;

import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.crypto.RandomXVmLease;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;

/**
 * Production proof-of-work provider preserving the existing RandomX modes:
 * dataset-backed hashing for mining and cache-only hashing for verification.
 */
public class RandomXProofOfWorkProvider implements ProofOfWorkProvider {

	private final RandomXManager randomXManager;

	public RandomXProofOfWorkProvider(RandomXManager randomXManager) {
		this.randomXManager = randomXManager;
	}

	@Override
	public void prepareForMining(long height) {
		try {
			randomXManager.prepareMiningResourcesForHeight(height);
		} catch (ProofOfWorkMiningException failure) {
			throw failure;
		} catch (RuntimeException | LinkageError failure) {
			throw new ProofOfWorkMiningException(
					"Failed to initialize RandomX mining resources for height " + height,
					failure);
		}
	}

	@Override
	public ProofOfWorkHasher openMiningHasher() {
		RandomXVmLease lease = randomXManager.createMiningVM();
		return new ProofOfWorkHasher(lease::calculateHash, lease::close);
	}

	@Override
	public ProofOfWorkHasher openVerificationHasher(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		RandomXVmLease lease = randomXManager.getLightVMForVerification(height, seedBlockProvider);
		return new ProofOfWorkHasher(lease::calculateHash, lease::close);
	}

	@Override
	public boolean isInitializationInProgress() {
		return randomXManager.isInitializationInProgress();
	}

	public boolean isDatasetAllocated() {
		return randomXManager.isDatasetAllocated();
	}

	public int getActiveVmLeaseCount() {
		return randomXManager.getActiveVmLeaseCount();
	}

	public RandomXMiningMemoryMode getMiningMemoryMode() {
		return randomXManager.getMiningMemoryMode();
	}

}
