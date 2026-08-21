/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
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
