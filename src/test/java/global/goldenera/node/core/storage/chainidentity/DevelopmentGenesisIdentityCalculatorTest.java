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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.NetworkSettings;

class DevelopmentGenesisIdentityCalculatorTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void calculatesWithoutCreatingOrOpeningTheConfiguredTargetPath() {
		Path target = temporaryDirectory.resolve("target-blockchain");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(
				GenesisConfigLoader.loadGenesisSettings(Network.MAINNET, "prod"),
				Network.MAINNET,
				"prod");

		String hash = new DevelopmentGenesisIdentityCalculator().calculate(settings);

		assertThat(hash).isEqualTo(
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f");
		assertThat(Files.exists(target)).isFalse();
	}
}
