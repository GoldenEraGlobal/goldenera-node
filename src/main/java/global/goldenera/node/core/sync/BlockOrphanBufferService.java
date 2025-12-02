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
package global.goldenera.node.core.sync;

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_SCHEDULER;
import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockOrphanBufferService {

	static final int MAX_ORPHANS = 1000;
	static final long ORPHAN_EXPIRATION_SECONDS = 600;

	MeterRegistry registry;
	ThreadPoolTaskScheduler coreScheduler;

	Map<Hash, List<OrphanBlockWrapper>> orphansByParent = new ConcurrentHashMap<>();
	Map<Hash, OrphanBlockWrapper> orphansByHash = new ConcurrentHashMap<>();

	public BlockOrphanBufferService(MeterRegistry registry,
			@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler) {
		this.coreScheduler = coreScheduler;
		this.registry = registry;

		registry.gaugeMapSize("blockchain.orphans.count", Tags.empty(), orphansByHash);
	}

	@PostConstruct
	public void init() {
		coreScheduler.scheduleAtFixedRate(this::cleanup, Duration.ofMillis(60000));
		log.info("BlockOrphanBufferService: Scheduled cleanup on coreTaskScheduler every 60s");
	}

	public void addOrphan(Block block, global.goldenera.cryptoj.datatypes.Address receivedFrom, Instant receivedAt) {
		if (orphansByHash.containsKey(block.getHash())) {
			return;
		}
		if (orphansByHash.size() >= MAX_ORPHANS) {
			log.warn("Orphan buffer full ({}). Dropping block {}", MAX_ORPHANS, block.getHeight());
			return;
		}

		OrphanBlockWrapper wrapper = new OrphanBlockWrapper(block, receivedFrom, receivedAt);
		orphansByHash.put(block.getHash(), wrapper);

		orphansByParent.computeIfAbsent(block.getHeader().getPreviousHash(), k -> new ArrayList<>()).add(wrapper);
	}

	public List<OrphanBlockWrapper> getAndRemoveChildren(Hash parentHash) {
		List<OrphanBlockWrapper> children = orphansByParent.remove(parentHash);
		if (children == null) {
			return new ArrayList<>();
		}
		for (OrphanBlockWrapper child : children) {
			orphansByHash.remove(child.getBlock().getHash());
		}
		return children;
	}

	public boolean isOrphan(Hash hash) {
		return orphansByHash.containsKey(hash);
	}

	/**
	 * Cleanup expired orphan blocks.
	 * Scheduled via coreTaskScheduler in init().
	 */
	public void cleanup() {
		Instant now = Instant.now();
		List<Hash> toRemove = new ArrayList<>();
		orphansByHash.forEach((hash, wrapper) -> {
			if (wrapper.getReceivedAt().plusSeconds(ORPHAN_EXPIRATION_SECONDS).isBefore(now)) {
				toRemove.add(hash);
			}
		});

		if (!toRemove.isEmpty()) {
			log.debug("Cleaning up {} expired orphan blocks", toRemove.size());
			for (Hash hash : toRemove) {
				OrphanBlockWrapper wrapper = orphansByHash.remove(hash);
				if (wrapper != null) {
					List<OrphanBlockWrapper> siblings = orphansByParent
							.get(wrapper.getBlock().getHeader().getPreviousHash());
					if (siblings != null) {
						siblings.remove(wrapper);
						if (siblings.isEmpty()) {
							orphansByParent.remove(wrapper.getBlock().getHeader().getPreviousHash());
						}
					}
				}
			}
		}
	}

	@Data
	@AllArgsConstructor
	public static class OrphanBlockWrapper {
		Block block;
		global.goldenera.cryptoj.datatypes.Address receivedFrom;
		Instant receivedAt;
	}
}