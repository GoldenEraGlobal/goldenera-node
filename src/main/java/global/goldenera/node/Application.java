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

import java.security.Security;
import java.util.Arrays;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import global.goldenera.node.core.sandbox.authoring.SandboxManifestAuthoringCli;
import global.goldenera.node.core.storage.chainidentity.DevelopmentGenesisAuthoringCli;
import global.goldenera.node.core.sync.snapshot.operator.OfflineSnapshotOperatorCli;

@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = {
                "global\\.goldenera\\.node\\.explorer\\..*",
                "global\\.goldenera\\.node\\.admin\\..*",
                "global\\.goldenera\\.node\\.shared\\.services\\.core\\..*",
                "global\\.goldenera\\.node\\.shared\\.services\\.webhook\\..*",
                "global\\.goldenera\\.node\\.shared\\.api\\.v1\\.webhook\\..*",
                "global\\.goldenera\\.node\\.shared\\.config\\.Webhook.*"
        }))
public class Application {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && DevelopmentGenesisAuthoringCli.COMMAND.equals(args[0])) {
            String[] authoringArguments = Arrays.copyOfRange(args, 1, args.length);
            int exitCode = DevelopmentGenesisAuthoringCli.execute(authoringArguments, System.out, System.err);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        if (args.length > 0 && SandboxManifestAuthoringCli.COMMAND.equals(args[0])) {
            String[] authoringArguments = Arrays.copyOfRange(args, 1, args.length);
            int exitCode = SandboxManifestAuthoringCli.execute(authoringArguments, System.out, System.err);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        if (args.length > 0 && OfflineSnapshotOperatorCli.COMMAND.equals(args[0])) {
            String[] operatorArguments = Arrays.copyOfRange(args, 1, args.length);
            int exitCode = OfflineSnapshotOperatorCli.execute(operatorArguments, System.out, System.err);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        SpringApplication.run(Application.class, args);
    }

}
