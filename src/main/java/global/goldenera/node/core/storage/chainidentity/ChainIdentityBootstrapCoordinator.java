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
package global.goldenera.node.core.storage.chainidentity;

import java.util.function.Supplier;

/** Coordinates read-only path inspection, local genesis proof, and Rocks binding. */
public final class ChainIdentityBootstrapCoordinator {

	private final ExpectedChainIdentityProvider expectedIdentityProvider;
	private final ChainIdentityPreflight preflight;
	private final Supplier<ChainStorageGuard> storageGuard;

	private ChainIdentityPreflightDecision decision;
	private ChainStorageGuardResult result;

	public ChainIdentityBootstrapCoordinator(
			ExpectedChainIdentityProvider expectedIdentityProvider,
			ChainIdentityPreflight preflight,
			ChainStorageGuard storageGuard) {
		this(expectedIdentityProvider, preflight, () -> storageGuard);
	}

	public ChainIdentityBootstrapCoordinator(
			ExpectedChainIdentityProvider expectedIdentityProvider,
			ChainIdentityPreflight preflight,
			Supplier<ChainStorageGuard> storageGuard) {
		this.expectedIdentityProvider = expectedIdentityProvider;
		this.preflight = preflight;
		this.storageGuard = storageGuard;
	}

	public synchronized ChainIdentityPreflightDecision preflightBeforeOpeningStorage() {
		if (decision == null) {
			decision = preflight.inspect(expectedIdentityProvider.expectedIdentity());
		}
		return decision;
	}

	public synchronized ChainStorageGuardResult bindAfterGenesisVerification(
			ChainIdentityBindingVerifier bindingVerifier) {
		if (decision == null) {
			throw new ChainStorageGuardException(
					"Chain identity cannot be bound before the read-only path preflight completes");
		}
		if (result == null) {
			ChainIdentityPreflightDecision refreshed = preflight.inspect(decision.expectation());
			bindingVerifier.verifyBeforeBinding(refreshed.expectation());
			result = storageGuard.get().verifyAndBind(refreshed.toGuardRequest());
		}
		return result;
	}
}
