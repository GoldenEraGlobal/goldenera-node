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
package global.goldenera.node.core.blockchain.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.node.core.sandbox.manifest.SandboxManifest.ClockMode;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

/** Selects a chain clock without ever mutating the host clock. */
@Configuration
public class ChainClockConfiguration {

	@Bean
	ChainClock chainClock(SandboxRuntimeContext runtimeContext) {
		if (!runtimeContext.isSandbox()) {
			return new ProductionChainClock();
		}
		var manifest = runtimeContext.manifestContext().orElseThrow().manifest();
		ClockMode mode = manifest.clock().mode();
		boolean deterministicMode = mode == ClockMode.DETERMINISTIC;
		if (deterministicMode != manifest.features().deterministicClock()) {
			throw new IllegalArgumentException("Sandbox clock mode and deterministic-clock feature must agree");
		}
		if (deterministicMode) {
			return new DeterministicSandboxChainClock(runtimeContext);
		}
		return new ProductionChainClock();
	}
}
