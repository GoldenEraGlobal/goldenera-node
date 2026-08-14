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
package global.goldenera.node.core.sandbox.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import global.goldenera.cryptoj.enums.Network;

public record SandboxActivationRequest(
		ExecutionDomain executionDomain,
		Set<String> activeProfiles,
		Network legacyWireNetwork,
		boolean directoryDisabled,
		boolean stressTestEnabled,
		Path manifestPath,
		Set<String> configuredSandboxOptions) {

	public SandboxActivationRequest {
		Objects.requireNonNull(executionDomain, "executionDomain");
		activeProfiles = Set.copyOf(Objects.requireNonNull(activeProfiles, "activeProfiles"));
		Objects.requireNonNull(legacyWireNetwork, "legacyWireNetwork");
		configuredSandboxOptions = Set.copyOf(
				Objects.requireNonNull(configuredSandboxOptions, "configuredSandboxOptions"));
	}
}
