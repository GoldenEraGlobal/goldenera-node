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
package global.goldenera.node.explorer.services.indexer.business;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;

/** Checks readiness before invoking the transactional mempool reset proxy. */
@Component
@RequiredArgsConstructor
public class ExIndexerMempoolStartupListener {

	private final GeneralProperties generalProperties;
	private final ExplorerRuntimeReadiness readiness;
	private final ObjectProvider<ExIndexerMempoolService> worker;

	@EventListener
	public void onCoreReady(CoreDbReadyEvent event) {
		if (!generalProperties.isExplorerEnable() || !readiness.isReady()) {
			return;
		}
		worker.getObject().resetOnCoreReady();
	}
}
