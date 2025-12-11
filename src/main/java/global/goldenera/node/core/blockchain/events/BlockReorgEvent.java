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

import static lombok.AccessLevel.PRIVATE;

import org.springframework.context.ApplicationEvent;

import global.goldenera.cryptoj.datatypes.Hash;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Event published when a blockchain reorganization occurs.
 * Contains information about the old chain tip that was replaced
 * and the new chain tip after the reorg.
 */
@Getter
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockReorgEvent extends ApplicationEvent {

    @NonNull
    Long oldHeight;
    @NonNull
    Hash oldHash;
    @NonNull
    Long newHeight;
    @NonNull
    Hash newHash;

    public BlockReorgEvent(Object source, Long oldHeight, Hash oldHash, Long newHeight, Hash newHash) {
        super(source);
        this.oldHeight = oldHeight;
        this.oldHash = oldHash;
        this.newHeight = newHeight;
        this.newHash = newHash;
    }
}
