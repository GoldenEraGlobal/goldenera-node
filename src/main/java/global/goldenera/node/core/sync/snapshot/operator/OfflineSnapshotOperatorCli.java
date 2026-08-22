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
package global.goldenera.node.core.sync.snapshot.operator;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import global.goldenera.node.Application;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService.PublicationResult;

/** Process entry point for one explicit offline combined snapshot publication. */
public final class OfflineSnapshotOperatorCli {

	public static final String COMMAND = "snapshot-publish";
	private OfflineSnapshotOperatorCli() {
	}

	public static int execute(String[] args, PrintStream output, PrintStream error) {
		if (args.length < 3 || args.length > 4) {
			error.println("usage: " + COMMAND
					+ " <ignored-height-or-current> <absolute-output-directory> <https-public-origin> [with-explorer]");
			return 2;
		}
		try {
			long requestedHeight = "current".equalsIgnoreCase(args[0]) ? -1 : Long.parseLong(args[0]);
			Path outputDirectory = Path.of(args[1]).normalize();
			URI publicOrigin = URI.create(args[2]);
			boolean includeExplorer = args.length == 4 && "with-explorer".equals(args[3]);
			if (requestedHeight < -1 || !outputDirectory.isAbsolute()
					|| args.length == 4 && !includeExplorer) {
				throw new IllegalArgumentException("Invalid current-head selector, output path or explorer option");
			}
			try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
					.web(WebApplicationType.NONE)
					.run(
							"--ge.snapshot.operator.enabled=true",
							"--ge.snapshot.operator.checkpoint-height=" + requestedHeight,
							"--ge.snapshot.operator.output-directory=" + outputDirectory,
							"--ge.snapshot.operator.public-origin=" + publicOrigin,
							"--ge.snapshot.operator.include-explorer=" + includeExplorer,
							"--ge.snapshot.operator.suppress-runtime=true",
							"--ge.core.sync.snapshot.bootstrap-enabled=false",
							"--ge.core.sync.snapshot.publish-enabled=false",
							"--ge.core.mining.enable=false")) {
				PublicationResult result = context.getBean(OfflineSnapshotOperatorService.class).publish();
				output.println("snapshot=" + result.publicationDirectory());
				output.println("checkpointHeight=" + result.stateManifest().checkpointHeight());
				output.println("checkpointHash=" + result.stateManifest().checkpointHash());
				output.println("coreStateSigningHash=" + result.stateManifestSigningHash());
				output.println("coreArchiveSigningHash=" + result.archiveManifestSigningHash());
				if (result.explorerManifest() != null) {
					output.println("explorerSigningHash=" + result.explorerManifest().signingHash());
				}
			}
			return 0;
		} catch (Exception e) {
			error.println("offline snapshot publication failed: " + safeMessage(e));
			return 1;
		}
	}

	private static String safeMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}
}
