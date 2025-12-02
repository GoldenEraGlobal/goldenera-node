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

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.converters.state.AccountNonceStateVersionConverter;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.enums.state.AccountNonceStateVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_account_nonce")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "address")
@IdClass(ExAccountNonce.AccountNoncePK.class)
public class ExAccountNonce implements AccountNonceState {

    @Id
    @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address address;

    @Column(name = "account_nonce_version", columnDefinition = "INTEGER")
    @Convert(converter = AccountNonceStateVersionConverter.class)
    AccountNonceStateVersion version;

    @Column(name = "nonce", nullable = false, updatable = true)
    long nonce;

    @Column(name = "created_at_block_height", nullable = false, updatable = false)
    long createdAtBlockHeight;

    @Column(name = "updated_at_block_height", nullable = false, updatable = true)
    long updatedAtBlockHeight;

    @Column(name = "created_at_timestamp", nullable = false, updatable = false)
    Instant createdAtTimestamp;

    @Column(name = "updated_at_timestamp", nullable = false, updatable = true)
    Instant updatedAtTimestamp;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class AccountNoncePK implements Serializable {

        @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address address;
    }

}
