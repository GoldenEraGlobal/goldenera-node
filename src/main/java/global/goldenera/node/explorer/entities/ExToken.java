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

import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.node.explorer.converters.state.TokenStateVersionConverter;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.HashConverter;
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
@Table(name = "explorer_token", indexes = {
        @Index(name = "idx_explorer_token_name", columnList = "name"),
        @Index(name = "idx_explorer_token_smallest_unit_name", columnList = "smallest_unit_name"),
        @Index(name = "idx_explorer_token_name_smallest_unit_name", columnList = "name, smallest_unit_name"),
        @Index(name = "idx_explorer_token_origin_tx_hash", columnList = "origin_tx_hash"),
        @Index(name = "idx_explorer_token_updated_by_tx_hash", columnList = "updated_by_tx_hash")
})
@Getter
@Setter
@NoArgsConstructor
@IdClass(ExToken.ExTokenPK.class)
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "address")
public class ExToken implements TokenState {

    @Id
    @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address address;

    @Column(name = "token_state_version", nullable = false, updatable = false, columnDefinition = "INTEGER")
    @Convert(converter = TokenStateVersionConverter.class)
    TokenStateVersion version;

    @Column(name = "name", nullable = false, updatable = true, length = 64)
    String name;

    @Column(name = "smallest_unit_name", nullable = false, updatable = true, length = 64)
    String smallestUnitName;

    @Column(name = "number_of_decimals", nullable = false, updatable = false)
    int numberOfDecimals;

    @Column(name = "website_url", nullable = true, updatable = true, length = 1024)
    String websiteUrl;

    @Column(name = "logo_url", nullable = true, updatable = true, length = 1024)
    String logoUrl;

    @Column(name = "max_supply", nullable = true, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
    BigInteger maxSupply;

    @Column(name = "user_burnable", nullable = false, updatable = false)
    boolean userBurnable;

    @Column(name = "origin_tx_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = HashConverter.class)
    Hash originTxHash;

    @Column(name = "updated_by_tx_hash", length = 32, nullable = false, updatable = true, columnDefinition = "BYTEA")
    @Convert(converter = HashConverter.class)
    Hash updatedByTxHash;

    @Column(name = "total_supply", nullable = false, updatable = true, precision = 80, scale = 0, columnDefinition = "NUMERIC")
    @Convert(converter = WeiConverter.class)
    Wei totalSupply;

    @Column(name = "created_at_block_height", nullable = false, updatable = false)
    long createdAtBlockHeight;

    @Column(name = "updated_at_block_height", nullable = false, updatable = true)
    long updatedAtBlockHeight;

    @Column(name = "created_at_timestamp", nullable = false, updatable = true)
    Instant createdAtTimestamp;

    @Column(name = "updated_at_timestamp", nullable = false, updatable = true)
    Instant updatedAtTimestamp;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class ExTokenPK implements Serializable {

        @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address address;
    }
}
