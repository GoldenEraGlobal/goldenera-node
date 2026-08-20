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
package global.goldenera.node.core.sandbox.control;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SandboxControlAuditLog {

	static final int MAX_EVENTS = 256;
	static final int MAX_PAGE_SIZE = 100;

	private final AtomicLong sequence = new AtomicLong();
	private final Deque<Event> events = new ArrayDeque<>(MAX_EVENTS);

	synchronized void record(Action action, String result, String operationId) {
		record(action, result, null, operationId);
	}

	synchronized void record(Action action, String result, String requestId, String operationId) {
		if (events.size() == MAX_EVENTS) {
			events.removeFirst();
		}
		long nextSequence = sequence.incrementAndGet();
		events.addLast(new Event(nextSequence, Instant.now(), action.name(), result, requestId, operationId));
		log.info("sandbox_control_audit sequence={} action={} result={} requestId={} operationId={}",
				nextSequence, action, result, requestId, operationId);
	}

	synchronized Page page(long after, int requestedLimit) {
		int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
		List<Event> result = new ArrayList<>(Math.min(limit, events.size()));
		boolean hasMore = false;
		for (Event event : events) {
			if (event.sequence() <= after) {
				continue;
			}
			if (result.size() == limit) {
				hasMore = true;
				break;
			}
			result.add(event);
		}
		Long nextAfter = result.isEmpty() ? null : result.getLast().sequence();
		return new Page(List.copyOf(result), hasMore, nextAfter);
	}

	enum Action {
		AUTHENTICATE,
		RATE_LIMIT,
		REQUEST_REJECTED,
		READ_CAPABILITIES,
		READ_STATE,
		SET_AUTONOMOUS,
		MINE_EXACTLY_ONE,
		MINE_EXACT_BATCH,
		AUTHOR_CANDIDATE,
		READ_OPERATION,
		READ_BLOCK_INGESTION,
		READ_SYNC_RUNTIME,
		READ_POW_RUNTIME,
		READ_EQUIVOCATION_RUNTIME,
		REQUEST_P2P_MAINTENANCE,
		CLEAR_MEMPOOL,
		READ_AUDIT
	}

	record Event(
			long sequence,
			Instant timestamp,
			String action,
			String result,
			String requestId,
			String operationId) {
	}

	record Page(List<Event> events, boolean hasMore, Long nextAfter) {
	}
}
