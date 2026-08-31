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
package global.goldenera.node.core.storage.blockchain.mempool;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalBootstrap;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;

@Component
@DependsOn(LifecycleJournalBootstrap.BEAN_NAME)
public final class PersistentMempoolBootstrap implements InitializingBean {

	private final PersistentMempoolRepository repository;
	private final LifecycleJournalQuery lifecycleJournal;

	public PersistentMempoolBootstrap(
			PersistentMempoolRepository repository,
			LifecycleJournalQuery lifecycleJournal) {
		this.repository = repository;
		this.lifecycleJournal = lifecycleJournal;
	}

	@Override
	public void afterPropertiesSet() {
		repository.initializeForEpoch(lifecycleJournal.head(LifecycleJournalStream.MEMPOOL).epoch());
		var canonicalHead = lifecycleJournal.head(LifecycleJournalStream.CANONICAL);
		repository.initializeCanonicalProjectionCursor(canonicalHead.epoch(), canonicalHead.sequence());
	}
}
