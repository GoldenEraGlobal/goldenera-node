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
package global.goldenera.node.explorer.entities;

import static lombok.AccessLevel.PRIVATE;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.explorer.converters.BlockVersionConverter;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.HashConverter;
import global.goldenera.node.shared.converters.SignatureConverter;
import global.goldenera.node.shared.converters.WeiConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_block_header", indexes = {
                @Index(name = "idx_block_previous_hash", columnList = "previous_hash"),
                @Index(name = "idx_block_height_desc", columnList = "height DESC"),
                @Index(name = "idx_block_timestamp", columnList = "timestamp"),
                @Index(name = "idx_block_coinbase_height", columnList = "coinbase, height DESC")
})
@IdClass(ExBlockHeader.ExBlockHeaderPK.class)
@Getter
@Setter
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "hash")
@NoArgsConstructor
public class ExBlockHeader implements BlockHeader {

        // --- Identifiers ---

        @Id
        @Convert(converter = HashConverter.class)
        @Column(name = "hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
        Hash hash;

        // --- Consensus Data ---

        @Column(name = "block_version", nullable = false, updatable = false, columnDefinition = "INTEGER")
        @Convert(converter = BlockVersionConverter.class)
        BlockVersion version;

        @Column(name = "height", nullable = false, updatable = false)
        long height;

        @Column(name = "timestamp", nullable = false, updatable = false)
        Instant timestamp;

        @Column(name = "previous_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = HashConverter.class)
        Hash previousHash;

        @Column(name = "tx_root_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = HashConverter.class)
        Hash txRootHash;

        @Column(name = "state_root_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = HashConverter.class)
        Hash stateRootHash;

        @Column(name = "difficulty", nullable = false, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
        BigInteger difficulty;

        @Column(name = "coinbase", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address coinbase;

        @Column(name = "nonce", nullable = false, updatable = false)
        long nonce;

        @Column(name = "signature", length = 65, nullable = true, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = SignatureConverter.class)
        Signature signature;

        // --- Explorer Calculated Data ---

        @Column(name = "block_size", nullable = false, updatable = false)
        int size;

        @Column(name = "cumulative_difficulty", nullable = false, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
        BigInteger cumulativeDifficulty;

        @Column(name = "number_of_txs", nullable = false, updatable = false)
        int numberOfTxs;

        @Column(name = "total_fees", nullable = false, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
        @Convert(converter = WeiConverter.class)
        Wei totalFees;

        @Column(name = "block_reward", nullable = false, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
        @Convert(converter = WeiConverter.class)
        Wei blockReward;

        // --- PK Class ---

        @NoArgsConstructor
        @AllArgsConstructor
        @EqualsAndHashCode
        @Getter
        @Setter
        public static class ExBlockHeaderPK implements Serializable {
                @Convert(converter = HashConverter.class)
                @Column(name = "hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
                Hash hash;
        }
}