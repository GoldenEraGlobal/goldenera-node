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
package global.goldenera.node.core.blockchain.events;

import java.util.List;

import org.springframework.context.ApplicationEvent;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import lombok.Getter;
import lombok.NonNull;

/**
 * Marks the publication boundary of a committed canonical block batch.
 *
 * <p>The individual {@link BlockConnectedEvent}s remain the source of truth for
 * per-block consumers. This event lets head-only consumers refresh once after
 * the whole committed batch has been published.</p>
 */
@Getter
public final class BlockConnectionBatchCompletedEvent extends ApplicationEvent {

	@NonNull
	private final ConnectedSource connectedSource;
	@NonNull
	private final List<BlockConnectedEvent> blockEvents;

	public BlockConnectionBatchCompletedEvent(Object source, ConnectedSource connectedSource,
			List<BlockConnectedEvent> blockEvents) {
		super(source);
		this.connectedSource = connectedSource;
		this.blockEvents = List.copyOf(blockEvents);
		if (this.blockEvents.isEmpty()) {
			throw new IllegalArgumentException("blockEvents must not be empty");
		}
	}

	public BlockConnectedEvent getTipEvent() {
		return blockEvents.get(blockEvents.size() - 1);
	}
}
