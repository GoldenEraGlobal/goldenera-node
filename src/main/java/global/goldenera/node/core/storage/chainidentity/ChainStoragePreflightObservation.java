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

import java.util.Objects;
import java.util.Optional;

/** Immutable result of one read-only storage-path inspection. */
public record ChainStoragePreflightObservation(
		String storeName,
		boolean identityStorageExists,
		Optional<StoredChainIdentity> identity,
		boolean hasChainData,
		Optional<String> observedGenesisHash) {

	public ChainStoragePreflightObservation {
		if (Objects.requireNonNull(storeName, "storeName").isBlank()) {
			throw new IllegalArgumentException("Store name must not be blank");
		}
		identity = Objects.requireNonNull(identity, "identity");
		observedGenesisHash = Objects.requireNonNull(observedGenesisHash, "observedGenesisHash");
		if (!identityStorageExists && identity.isPresent()) {
			throw new IllegalArgumentException("Identity cannot exist without its storage");
		}
		observedGenesisHash.ifPresent(hash -> {
			if (!hash.matches("^0x[0-9a-f]{64}$")) {
				throw new IllegalArgumentException("Observed genesis hash must be lowercase 32-byte hex");
			}
		});
	}
}
