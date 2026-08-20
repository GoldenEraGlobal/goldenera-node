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

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.NetworkSettings;

/** Offline hash author for an explicitly supplied development genesis document. */
public final class DevelopmentGenesisAuthoringCli {

	public static final String COMMAND = "development-genesis-author";
	public static final String NETWORK_FORK_SCHEDULE = "--network-fork-schedule";

	private DevelopmentGenesisAuthoringCli() {
	}

	public static int execute(String[] args, PrintStream output, PrintStream error) {
		if (args.length < 1 || args.length > 2
				|| (args.length == 2 && !NETWORK_FORK_SCHEDULE.equals(args[1]))) {
			error.println("usage: " + COMMAND
					+ " <absolute-genesis.json> [" + NETWORK_FORK_SCHEDULE + "]");
			return 2;
		}
		try {
			Path input = Path.of(args[0]).toAbsolutePath().normalize();
			if (!Files.isRegularFile(input)) {
				throw new IllegalArgumentException("development genesis input is not a regular file");
			}
			var root = new ObjectMapper().readTree(input.toFile());
			var genesis = GenesisConfigLoader.parseProductionGenesisSettings(root, Network.TESTNET);
			String consensusProfile = args.length == 2 ? "prod" : "dev";
			var settings = NetworkSettings.fromGenesisSettings(genesis, Network.TESTNET, consensusProfile);
			String hash = new DevelopmentGenesisIdentityCalculator().calculate(settings);
			output.println("genesisHash=" + hash);
			return 0;
		} catch (Exception exception) {
			String message = exception.getMessage();
			error.println("development genesis authoring failed: "
					+ (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
			return 1;
		}
	}
}
