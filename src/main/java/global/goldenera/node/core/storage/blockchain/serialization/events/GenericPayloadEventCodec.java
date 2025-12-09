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
package global.goldenera.node.core.storage.blockchain.serialization.events;

import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadDecoder;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

/**
 * Generic codec for BlockEvents that contain a BipHash and a specific
 * TxPayload.
 */
public class GenericPayloadEventCodec<T extends BlockEvent, P extends TxPayload> implements BlockEventCodec<T> {

    @FunctionalInterface
    public interface EventFactory<T, P> {
        T create(Hash bipHash, TxVersion version, P payload);
    }

    private final EventFactory<T, P> factory;

    // Interface to access fields from the event instance for encoding
    @FunctionalInterface
    public interface PayloadExtractor<T, P> {
        P extract(T event);
    }

    @FunctionalInterface
    public interface VersionExtractor<T> {
        TxVersion extract(T event);
    }

    private final PayloadExtractor<T, P> payloadExtractor;
    private final VersionExtractor<T> versionExtractor;

    public GenericPayloadEventCodec(
            EventFactory<T, P> factory,
            PayloadExtractor<T, P> payloadExtractor,
            VersionExtractor<T> versionExtractor) {
        this.factory = factory;
        this.payloadExtractor = payloadExtractor;
        this.versionExtractor = versionExtractor;
    }

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, T event, int version) {
        out.writeBytes32(event.bipHash());

        TxVersion txVersion = versionExtractor.extract(event);
        out.writeIntScalar(txVersion.getCode());

        P payload = payloadExtractor.extract(event);
        out.writeRaw(TxPayloadEncoder.INSTANCE.encode(payload, txVersion));
    }

    @SuppressWarnings("unchecked")
    @Override
    public T decode(RLPInput input, int version) {
        Hash bipHash = Hash.wrap(input.readBytes32());

        int txVersionCode = input.readIntScalar();
        TxVersion txVersion = TxVersion.fromCode(txVersionCode);

        P payload = (P) TxPayloadDecoder.INSTANCE.decode(input.readRaw(), txVersion);

        return factory.create(bipHash, txVersion, payload);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
