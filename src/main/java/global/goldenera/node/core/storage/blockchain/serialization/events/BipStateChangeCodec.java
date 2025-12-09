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
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BipStateChange;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

public class BipStateChangeCodec implements BlockEventCodec<BipStateChange> {

    public static final BipStateChangeCodec INSTANCE = new BipStateChangeCodec();

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, BipStateChange event, int version) {
        out.writeBytes32(event.bipHash());
        out.writeIntScalar(event.status().getCode());
        out.writeIntScalar(event.isActionExecuted() ? 1 : 0);

        // Approvers
        out.startList();
        if (event.approvers() != null) {
            for (Address approver : event.approvers()) {
                out.writeBytes(approver);
            }
        }
        out.endList();

        // Disapprovers
        out.startList();
        if (event.disapprovers() != null) {
            for (Address disapprover : event.disapprovers()) {
                out.writeBytes(disapprover);
            }
        }
        out.endList();

        out.writeBytes32(event.updatedByTxHash());
        out.writeLongScalar(event.updatedAtBlockHeight());
        out.writeLongScalar(event.updatedAtTimestamp().toEpochMilli());
    }

    @Override
    public BipStateChange decode(RLPInput input, int version) {
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

        return new BipStateChange(
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
