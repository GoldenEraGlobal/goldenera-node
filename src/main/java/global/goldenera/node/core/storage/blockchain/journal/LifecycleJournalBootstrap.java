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
package global.goldenera.node.core.storage.blockchain.journal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityBindingInitializer;

@Component(LifecycleJournalBootstrap.BEAN_NAME)
@DependsOn(ChainIdentityBindingInitializer.BEAN_NAME)
public final class LifecycleJournalBootstrap implements InitializingBean {

	public static final String BEAN_NAME = "lifecycleJournalBootstrap";

	private final LifecycleJournalRepository repository;
	private final BlockRepository blockRepository;

	public LifecycleJournalBootstrap(
			LifecycleJournalRepository repository,
			BlockRepository blockRepository) {
		this.repository = repository;
		this.blockRepository = blockRepository;
	}

	@Override
	public void afterPropertiesSet() {
		StoredBlock head = blockRepository.getLatestStoredBlock().orElse(null);
		long anchorHeight = head == null ? -1L : head.getHeight();
		Hash anchorHash = head == null ? Hash.ZERO : head.getHash();
		repository.initializeAnchorIfMissing(anchorHeight, anchorHash);
	}
}
