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

import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

/**
 * Codec interface for a specific BlockEvent type.
 * Each event type (TokenCreated, TokenMinted, etc.) has its own codec with its
 * own versioning.
 * This allows changing serialization of one event type without affecting
 * others.
 *
 * @param <T>
 *            The specific BlockEvent subtype this codec handles
 */
public interface BlockEventCodec<T extends BlockEvent> {

    /**
     * @return The current version code for this event type's serialization
     */
    int currentVersion();

    /**
     * Encodes the event data (without type/version header - those are handled by
     * parent encoder).
     */
    void encode(RLPOutput out, T event, int version);

    /**
     * Decodes the event data (type/version already read by parent decoder).
     */
    T decode(RLPInput input, int version);

    /**
     * @return true if this codec supports the given version
     */
    boolean supportsVersion(int version);
}
