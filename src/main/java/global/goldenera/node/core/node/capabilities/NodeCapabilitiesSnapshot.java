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
package global.goldenera.node.core.node.capabilities;

import java.util.List;
import java.util.Objects;

import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

public record NodeCapabilitiesSnapshot(
		int contractVersion,
		ExecutionDomain executionDomain,
		StoredChainIdentity chainIdentity,
		ProofOfWorkRuntimeMode proofOfWorkMode,
		List<String> capabilityIds) {

	public NodeCapabilitiesSnapshot {
		if (contractVersion != 1) {
			throw new IllegalArgumentException("Unsupported node capability contract version");
		}
		Objects.requireNonNull(executionDomain, "executionDomain");
		Objects.requireNonNull(chainIdentity, "chainIdentity");
		Objects.requireNonNull(proofOfWorkMode, "proofOfWorkMode");
		capabilityIds = List.copyOf(capabilityIds);
		if (!capabilityIds.equals(capabilityIds.stream().sorted().distinct().toList())) {
			throw new IllegalArgumentException("Capability IDs must be unique and sorted");
		}
	}
}
