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

import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "ge.snapshot.operator", ignoreUnknownFields = false)
public class OfflineSnapshotOperatorProperties {

	private boolean enabled;
	private long checkpointHeight = -1;
	private Path outputDirectory;
	private URI publicOrigin;
	private int explorerChunkBytes = 8 * 1024 * 1024;
	private boolean includeExplorer;
	private boolean cloneExportContext;
	private boolean suppressRuntime;

	public void validate(boolean allowHttpForTesting) {
		if (!enabled || checkpointHeight < -1 || outputDirectory == null || publicOrigin == null) {
			throw new IllegalStateException("Offline snapshot operator configuration is incomplete");
		}
		if (!outputDirectory.isAbsolute() || !outputDirectory.equals(outputDirectory.normalize())) {
			throw new IllegalStateException("Offline snapshot output must be a normalized absolute path");
		}
		boolean secureOrigin = "https".equalsIgnoreCase(publicOrigin.getScheme());
		boolean explicitTestOrigin = allowHttpForTesting && "http".equalsIgnoreCase(publicOrigin.getScheme());
		if ((!secureOrigin && !explicitTestOrigin) || publicOrigin.getHost() == null
				|| publicOrigin.getUserInfo() != null || publicOrigin.getQuery() != null
				|| publicOrigin.getFragment() != null) {
			throw new IllegalStateException("Production snapshot public origin must be an absolute HTTPS origin");
		}
	}
}
