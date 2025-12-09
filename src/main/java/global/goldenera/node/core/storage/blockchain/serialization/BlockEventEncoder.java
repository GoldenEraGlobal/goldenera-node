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
package global.goldenera.node.core.storage.blockchain.serialization;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import global.goldenera.node.core.enums.BlockEventType;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.serialization.events.BipStateChangeCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.BlockEventCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.BlockRewardCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.FeesCollectedCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.GenericPayloadEventCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.TokenBurnedCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.TokenCreatedCodec;
import global.goldenera.node.core.storage.blockchain.serialization.events.TokenSupplyUpdatedCodec;
import global.goldenera.rlp.RLPOutput;

/**
 * Encodes BlockEvent records to RLP format for storage.
 * Each event type has its own codec with independent versioning.
 * Format per event: [eventType, eventVersion, ...eventData]
 * This allows changing serialization of one event type without affecting
 * others.
 */
public class BlockEventEncoder {

	public static final BlockEventEncoder INSTANCE = new BlockEventEncoder();

	@SuppressWarnings("rawtypes")
	private final Map<BlockEventType, BlockEventCodec> codecs = new EnumMap<>(BlockEventType.class);

	private BlockEventEncoder() {
		codecs.put(BlockEventType.BLOCK_REWARD, BlockRewardCodec.INSTANCE);
		codecs.put(BlockEventType.FEES_COLLECTED, FeesCollectedCodec.INSTANCE);
		codecs.put(BlockEventType.TOKEN_CREATED, TokenCreatedCodec.INSTANCE);
		codecs.put(BlockEventType.TOKEN_UPDATED, new GenericPayloadEventCodec<>(BlockEvent.TokenUpdated::new,
				BlockEvent.TokenUpdated::payload, BlockEvent.TokenUpdated::txVersion));
		codecs.put(BlockEventType.TOKEN_MINTED, new GenericPayloadEventCodec<>(BlockEvent.TokenMinted::new,
				BlockEvent.TokenMinted::payload, BlockEvent.TokenMinted::txVersion));
		codecs.put(BlockEventType.TOKEN_BURNED, TokenBurnedCodec.INSTANCE);
		codecs.put(BlockEventType.TOKEN_SUPPLY_UPDATED, TokenSupplyUpdatedCodec.INSTANCE);
		codecs.put(BlockEventType.AUTHORITY_ADDED, new GenericPayloadEventCodec<>(BlockEvent.AuthorityAdded::new,
				BlockEvent.AuthorityAdded::payload, BlockEvent.AuthorityAdded::txVersion));
		codecs.put(BlockEventType.AUTHORITY_REMOVED, new GenericPayloadEventCodec<>(BlockEvent.AuthorityRemoved::new,
				BlockEvent.AuthorityRemoved::payload, BlockEvent.AuthorityRemoved::txVersion));
		codecs.put(BlockEventType.NETWORK_PARAMS_CHANGED,
				new GenericPayloadEventCodec<>(BlockEvent.NetworkParamsChanged::new,
						BlockEvent.NetworkParamsChanged::payload, BlockEvent.NetworkParamsChanged::txVersion));
		codecs.put(BlockEventType.ADDRESS_ALIAS_ADDED, new GenericPayloadEventCodec<>(BlockEvent.AddressAliasAdded::new,
				BlockEvent.AddressAliasAdded::payload, BlockEvent.AddressAliasAdded::txVersion));
		codecs.put(BlockEventType.ADDRESS_ALIAS_REMOVED,
				new GenericPayloadEventCodec<>(BlockEvent.AddressAliasRemoved::new,
						BlockEvent.AddressAliasRemoved::payload, BlockEvent.AddressAliasRemoved::txVersion));
		codecs.put(BlockEventType.BIP_STATE_CHANGE, BipStateChangeCodec.INSTANCE);
	}

	/**
	 * Encodes a list of events to RLP.
	 * Each event: [eventType, eventVersion, ...eventData]
	 */
	public void encodeList(RLPOutput out, List<BlockEvent> events) {
		out.startList();
		if (events != null) {
			for (BlockEvent event : events) {
				encode(out, event);
			}
		}
		out.endList();
	}

	@SuppressWarnings("unchecked")
	public void encode(RLPOutput out, BlockEvent event) {
		BlockEventType type = event.type();
		BlockEventCodec<BlockEvent> codec = codecs.get(type);
		if (codec == null) {
			throw new IllegalArgumentException("No codec registered for event type: " + type);
		}

		out.startList();
		out.writeIntScalar(type.getCode());
		out.writeIntScalar(codec.currentVersion());
		codec.encode(out, event, codec.currentVersion());
		out.endList();
	}
}
