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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DevelopmentGenesisAuthoringCliTest {

	@Test
	void authorsTheCanonicalHashWithoutStartingSpring() throws Exception {
		Path genesis = Path.of(getClass().getResource("/genesis/genesis-testnet-prod.json").toURI());
		var output = new ByteArrayOutputStream();
		var error = new ByteArrayOutputStream();

		int exit = DevelopmentGenesisAuthoringCli.execute(
				new String[] {genesis.toString()}, new PrintStream(output), new PrintStream(error));

		assertThat(exit).isZero();
		assertThat(error.size()).isZero();
		assertThat(output.toString()).matches("genesisHash=0x[0-9a-f]{64}\\R");
	}

	@Test
	void authorsUsingTheNetworkForkScheduleWhenRequested() throws Exception {
		Path genesis = Path.of(getClass().getResource("/genesis/genesis-testnet-prod.json").toURI());
		var output = new ByteArrayOutputStream();
		var error = new ByteArrayOutputStream();

		int exit = DevelopmentGenesisAuthoringCli.execute(
				new String[] {genesis.toString(), DevelopmentGenesisAuthoringCli.NETWORK_FORK_SCHEDULE},
				new PrintStream(output), new PrintStream(error));

		assertThat(exit).isZero();
		assertThat(error.size()).isZero();
		assertThat(output.toString()).matches("genesisHash=0x[0-9a-f]{64}\\R");
	}
}
