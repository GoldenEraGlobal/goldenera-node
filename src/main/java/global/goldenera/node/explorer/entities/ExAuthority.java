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
import java.time.Instant;

import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.node.explorer.converters.state.AuthorityStateVersionConverter;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.HashConverter;
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
@Table(name = "explorer_authority", indexes = {
        @Index(name = "idx_explorer_authority_origin_tx_hash", columnList = "origin_tx_hash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "address")
@IdClass(ExAuthority.AuthorityPK.class)
public class ExAuthority implements AuthorityState {

    @Id
    @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address address;

    @Column(name = "authority_version", columnDefinition = "INTEGER")
    @Convert(converter = AuthorityStateVersionConverter.class)
    AuthorityStateVersion version;

    @Column(name = "origin_tx_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = HashConverter.class)
    Hash originTxHash;

    @Column(name = "created_at_block_height", nullable = false, updatable = false)
    long createdAtBlockHeight;

    @Column(name = "created_at_timestamp", nullable = false, updatable = false)
    Instant createdAtTimestamp;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class AuthorityPK implements Serializable {

        @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address address;
    }
}
