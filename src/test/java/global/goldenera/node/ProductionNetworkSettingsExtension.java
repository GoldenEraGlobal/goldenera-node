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
package global.goldenera.node;

import java.util.Optional;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.mock.env.MockEnvironment;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.shared.properties.GeneralProperties;

/**
 * Installs the frozen MAINNET production settings before every Jupiter test.
 * Tests which exercise uninitialized or sandbox-only behavior explicitly reset
 * the provider after this extension has run.
 */
public final class ProductionNetworkSettingsExtension implements BeforeEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		install(Network.MAINNET);
	}

	/** Installs frozen production settings for an explicit network in compatibility tests. */
	public static void install(Network network) {
		NetworkSettingsProvider.resetForTesting();

		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setNetwork(network);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("prod");
		SandboxRuntimeContext runtimeContext = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION,
				network,
				Optional.empty());

		new NetworkSettingsProvider(generalProperties, environment, runtimeContext).init();
	}
}
