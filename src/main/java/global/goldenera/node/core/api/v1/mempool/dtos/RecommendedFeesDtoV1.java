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
package global.goldenera.node.core.api.v1.mempool.dtos;

import static lombok.AccessLevel.PRIVATE;

import org.apache.tuweni.units.ethereum.Wei;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * DTO for recommended transaction fees based on current mempool state.
 * All fees are in Wei (smallest unit).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
public class RecommendedFeesDtoV1 {

    /**
     * Slow/Economy fee - minimum required fee based on network parameters.
     * Transaction may take longer to confirm.
     */
    FeeLevel slow;

    /**
     * Standard fee - median fee in the mempool.
     * Typical confirmation time.
     */
    FeeLevel standard;

    /**
     * Fast/Priority fee - fee to get into top 20% of mempool.
     * Faster confirmation.
     */
    FeeLevel fast;

    /**
     * Current number of transactions in mempool.
     */
    long mempoolSize;

    /**
     * Represents a fee level with base fee and per-byte fee.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = PRIVATE)
    public static class FeeLevel {
        /**
         * Base fee for the transaction (fixed cost).
         */
        Wei baseFee;

        /**
         * Fee per byte of transaction size.
         */
        Wei feePerByte;

        /**
         * Total fee for a transaction of average size (250 bytes).
         * Calculated as: baseFee + (feePerByte * 250)
         */
        Wei totalForAverageTx;
    }
}
