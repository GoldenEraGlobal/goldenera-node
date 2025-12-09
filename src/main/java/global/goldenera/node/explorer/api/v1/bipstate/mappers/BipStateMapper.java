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
package global.goldenera.node.explorer.api.v1.bipstate.mappers;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.node.explorer.api.v1.bipstate.dtos.BipStateDtoV1;
import global.goldenera.node.explorer.api.v1.bipstate.dtos.BipStateDtoV1_Page;
import global.goldenera.node.explorer.api.v1.bipstate.dtos.BipStateMetadataDtoV1;
import global.goldenera.node.explorer.api.v1.tx.mappers.TxMapper;
import global.goldenera.node.explorer.entities.ExBipState;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BipStateMapper {

    TxMapper txMapper;

    public BipStateDtoV1 map(@NonNull ExBipState bipState) {
        BipStatus status = bipState.getStatus();

        if (status == BipStatus.PENDING) {
            Instant now = Instant.now();
            if (now.isAfter(bipState.getExpirationTimestamp())) {
                status = BipStatus.EXPIRED;
            }
        }

        return new BipStateDtoV1(
                bipState.getVersion(),
                bipState.getBipHash(),
                status,
                bipState.isActionExecuted(),
                bipState.getType(),
                bipState.getNumberOfRequiredVotes(),
                bipState.getApprovers(),
                bipState.getDisapprovers(),
                bipState.getExecutedAtTimestamp(),
                bipState.getExpirationTimestamp(),
                bipState.getCreatedAtBlockHeight(),
                bipState.getCreatedAtTimestamp(),
                bipState.getUpdatedAtBlockHeight(),
                bipState.getUpdatedAtTimestamp(),
                bipState.getUpdatedByTxHash(),
                mapMetadata(bipState));
    }

    public List<BipStateDtoV1> map(@NonNull List<ExBipState> bipStates) {
        return bipStates.stream().map(this::map).toList();
    }

    public BipStateDtoV1_Page map(@NonNull Page<ExBipState> bipStates) {
        return new BipStateDtoV1_Page(
                map(bipStates.toList()),
                bipStates.getTotalPages(),
                bipStates.getTotalElements());
    }

    public BipStateMetadataDtoV1 mapMetadata(@NonNull ExBipState bipState) {
        return new BipStateMetadataDtoV1(
                bipState.getMetadata().getVersion(),
                bipState.getMetadata().getTxVersion(),
                bipState.getMetadata().getDerivedTokenAddress(),
                txMapper.mapPayload(bipState.getMetadata().getTxPayload()));
    }
}
