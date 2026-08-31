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
package global.goldenera.node.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

/** Storage policy for node-local equivocation monitoring data. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.core.equivocation", ignoreUnknownFields = false)
public class EquivocationProperties {

	/**
	 * Number of heights for which a non-conflicting observation is retained behind
	 * the highest contextually validated height seen by this node. Once enabled, a
	 * first observation older than the window can no longer establish a future
	 * conflict after it is pruned. Already detected conflicts remain permanent, and
	 * a retained legacy singleton may still be promoted to a permanent conflict.
	 * Zero preserves the backward-compatible, unbounded detection horizon.
	 */
	long singleObservationRetentionBlocks;

	/** Maximum number of legacy records examined by one maintenance write. */
	int pruneBatchSize = 1_000;

	@PostConstruct
	void validate() {
		if (singleObservationRetentionBlocks < 0) {
			throw new IllegalArgumentException("Equivocation retention blocks cannot be negative");
		}
		if (pruneBatchSize < 1 || pruneBatchSize > 100_000) {
			throw new IllegalArgumentException("Equivocation prune batch size must be between 1 and 100000");
		}
	}
}
