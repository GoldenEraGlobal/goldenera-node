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

import java.time.Instant;
import java.util.LinkedHashSet;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BipStateCreated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BipStateUpdated;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

/**
 * Generic codec for BIP state events (Created and Updated).
 * Both events have the same structure so we use a factory function.
 *
 * @param <T>
 *            The type of BlockEvent (BipStateCreated or BipStateUpdated)
 */
public class BipStateCodec<T extends BlockEvent> implements BlockEventCodec<T> {

    public static final BipStateCodec<BipStateCreated> CREATED_INSTANCE = new BipStateCodec<>(
            BipStateCreated::new);
    public static final BipStateCodec<BipStateUpdated> UPDATED_INSTANCE = new BipStateCodec<>(
            BipStateUpdated::new);

    @FunctionalInterface
    public interface BipStateFactory<T> {
        T create(Hash bipHash,
                BipStatus status,
                boolean isActionExecuted,
                LinkedHashSet<Address> approvers,
                LinkedHashSet<Address> disapprovers,
                Hash updatedByTxHash,
                long updatedAtBlockHeight,
                Instant updatedAtTimestamp);
    }

    private final BipStateFactory<T> factory;

    private BipStateCodec(BipStateFactory<T> factory) {
        this.factory = factory;
    }

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, T event, int version) {
        // Use accessor methods via reflection-free approach
        Hash bipHash;
        BipStatus status;
        boolean isActionExecuted;
        LinkedHashSet<Address> approvers;
        LinkedHashSet<Address> disapprovers;
        Hash updatedByTxHash;
        long updatedAtBlockHeight;
        Instant updatedAtTimestamp;

        if (event instanceof BipStateCreated e) {
            bipHash = e.bipHash();
            status = e.status();
            isActionExecuted = e.isActionExecuted();
            approvers = e.approvers();
            disapprovers = e.disapprovers();
            updatedByTxHash = e.updatedByTxHash();
            updatedAtBlockHeight = e.updatedAtBlockHeight();
            updatedAtTimestamp = e.updatedAtTimestamp();
        } else if (event instanceof BipStateUpdated e) {
            bipHash = e.bipHash();
            status = e.status();
            isActionExecuted = e.isActionExecuted();
            approvers = e.approvers();
            disapprovers = e.disapprovers();
            updatedByTxHash = e.updatedByTxHash();
            updatedAtBlockHeight = e.updatedAtBlockHeight();
            updatedAtTimestamp = e.updatedAtTimestamp();
        } else {
            throw new IllegalArgumentException("Unknown BIP state event type: " + event.getClass());
        }

        out.writeBytes32(bipHash);
        out.writeIntScalar(status.getCode());
        out.writeIntScalar(isActionExecuted ? 1 : 0);

        // Approvers
        out.startList();
        if (approvers != null && !approvers.isEmpty()) {
            for (Address approver : approvers) {
                out.writeBytes(approver);
            }
        }
        out.endList();

        // Disapprovers
        out.startList();
        if (disapprovers != null && !disapprovers.isEmpty()) {
            for (Address disapprover : disapprovers) {
                out.writeBytes(disapprover);
            }
        }
        out.endList();

        out.writeBytes32(updatedByTxHash);
        out.writeLongScalar(updatedAtBlockHeight);
        out.writeLongScalar(updatedAtTimestamp.toEpochMilli());
    }

    @Override
    public T decode(RLPInput input, int version) {
        Hash bipHash = Hash.wrap(input.readBytes32());
        BipStatus status = BipStatus.fromCode(input.readIntScalar());
        boolean isActionExecuted = input.readIntScalar() == 1;

        // Approvers
        int approversCount = input.enterList();
        LinkedHashSet<Address> approvers = new LinkedHashSet<>(approversCount);
        for (int i = 0; i < approversCount; i++) {
            approvers.add(Address.wrap(input.readBytes()));
        }
        input.leaveList();

        // Disapprovers
        int disapproversCount = input.enterList();
        LinkedHashSet<Address> disapprovers = new LinkedHashSet<>(disapproversCount);
        for (int i = 0; i < disapproversCount; i++) {
            disapprovers.add(Address.wrap(input.readBytes()));
        }
        input.leaveList();

        Hash updatedByTxHash = Hash.wrap(input.readBytes32());
        long updatedAtBlockHeight = input.readLongScalar();
        Instant updatedAtTimestamp = Instant.ofEpochMilli(input.readLongScalar());

        return factory.create(
                bipHash,
                status,
                isActionExecuted,
                approvers,
                disapprovers,
                updatedByTxHash,
                updatedAtBlockHeight,
                updatedAtTimestamp);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
