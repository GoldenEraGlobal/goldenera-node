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
import java.util.Objects;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.converters.state.AccountBalanceStateVersionConverter;
import global.goldenera.node.shared.consensus.state.AccountBalanceState;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.WeiConverter;
import global.goldenera.node.shared.enums.state.AccountBalanceStateVersion;
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
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_account_balance", indexes = {
        @Index(name = "idx_explorer_account_balance_balance", columnList = "balance"),
        @Index(name = "idx_explorer_account_balance_token_balance", columnList = "token_address, balance")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@IdClass(ExAccountBalance.ExAccountBalancePK.class)
@EqualsAndHashCode(of = { "address", "tokenAddress" })
public class ExAccountBalance implements AccountBalanceState {

    @Id
    @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address address;

    @Id
    @Column(name = "token_address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address tokenAddress;

    @Column(name = "account_balance_version", columnDefinition = "INTEGER")
    @Convert(converter = AccountBalanceStateVersionConverter.class)
    AccountBalanceStateVersion version;

    @Column(name = "balance", nullable = false, updatable = true, precision = 80, scale = 0, columnDefinition = "NUMERIC")
    @Convert(converter = WeiConverter.class)
    Wei balance;

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
    @Getter
    @FieldDefaults(level = PRIVATE)
    public static class ExAccountBalancePK implements Serializable {
        @NonNull
        @Column(name = "address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address address;

        @NonNull
        @Column(name = "token_address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
        @Convert(converter = AddressConverter.class)
        Address tokenAddress;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ExAccountBalancePK that = (ExAccountBalancePK) o;
            return Objects.equals(address, that.address) && Objects.equals(tokenAddress, that.tokenAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, tokenAddress);
        }
    }
}
