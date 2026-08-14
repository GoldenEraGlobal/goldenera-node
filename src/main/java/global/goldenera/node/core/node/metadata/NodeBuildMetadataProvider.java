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
package global.goldenera.node.core.node.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public final class NodeBuildMetadataProvider {

	static final String UNKNOWN = "UNKNOWN";
	private static final String VERSION_RESOURCE = "version.properties";
	private static final Pattern GIT_COMMIT = Pattern.compile("^[0-9a-f]{40,64}$");
	private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

	private final NodeBuildMetadata metadata;

	public NodeBuildMetadataProvider(Environment environment) {
		this(loadVersionProperties(), environment);
	}

	NodeBuildMetadataProvider(Properties properties, Environment environment) {
		this(properties,
				Boolean.parseBoolean(property(properties, "release.metadata.required"))
						|| environment.acceptsProfiles(Profiles.of("release"))
						|| environment.getProperty("ge.build.metadata.required", Boolean.class, false));
	}

	NodeBuildMetadataProvider(Properties properties, boolean required) {
		metadata = new NodeBuildMetadata(
				property(properties, "app.version"),
				property(properties, "git.commit"),
				property(properties, "cryptoj.version"),
				property(properties, "cryptoj.sha256"),
				property(properties, "randomx.source.commit"),
				System.getProperty("java.version", UNKNOWN),
				System.getProperty("java.vendor", UNKNOWN),
				System.getProperty("java.vm.name", UNKNOWN),
				System.getProperty("os.name", UNKNOWN),
				System.getProperty("os.arch", UNKNOWN));
		if (required) {
			List<String> missing = requiredMetadata().stream()
					.filter(key -> UNKNOWN.equals(property(properties, key)))
					.toList();
			if (!missing.isEmpty()) {
				throw new IllegalStateException("Release build metadata is missing: " + missing);
			}
			if (!GIT_COMMIT.matcher(metadata.gitCommit()).matches()) {
				throw new IllegalStateException("Release git commit must be lowercase hexadecimal (40..64 chars)");
			}
			if (!SHA_256.matcher(metadata.cryptoJSha256()).matches()) {
				throw new IllegalStateException("Release CryptoJ checksum must be an exact lowercase SHA-256");
			}
			if (!GIT_COMMIT.matcher(metadata.randomXSourceCommit()).matches()
					|| metadata.randomXSourceCommit().length() != 40) {
				throw new IllegalStateException("Release RandomX source commit must be exact lowercase 40-char hexadecimal");
			}
		}
	}

	public NodeBuildMetadata metadata() {
		return metadata;
	}

	private static Properties loadVersionProperties() {
		Properties properties = new Properties();
		try (InputStream input = NodeBuildMetadataProvider.class.getClassLoader()
				.getResourceAsStream(VERSION_RESOURCE)) {
			if (input != null) {
				properties.load(input);
			}
			return properties;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot load " + VERSION_RESOURCE, e);
		}
	}

	private static List<String> requiredMetadata() {
		return List.of("app.version", "git.commit", "cryptoj.version", "cryptoj.sha256", "randomx.source.commit");
	}

	private static String property(Properties properties, String key) {
		String value = properties.getProperty(key, UNKNOWN).trim();
		return value.isBlank() || value.startsWith("@") ? UNKNOWN : value;
	}
}
