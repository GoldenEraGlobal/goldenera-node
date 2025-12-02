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

import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.converters.TransferTypeConverter;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.HashConverter;
import global.goldenera.node.shared.converters.WeiConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_transfer", indexes = {
		@Index(name = "idx_ex_transfer_from", columnList = "from_address, block_height DESC"),
		@Index(name = "idx_ex_transfer_to", columnList = "to_address, block_height DESC"),
		@Index(name = "idx_ex_transfer_token", columnList = "token_address, block_height DESC"),
		@Index(name = "idx_ex_transfer_tx_hash", columnList = "tx_hash")
})
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
public class ExTransfer {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transfer_seq_gen")
	@SequenceGenerator(name = "transfer_seq_gen", sequenceName = "explorer_transfer_seq", allocationSize = 1)
	Long id;

	@Column(name = "block_height", nullable = false, updatable = false)
	long blockHeight;

	@Column(name = "block_hash", nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash blockHash;

	@Column(name = "timestamp", nullable = false, updatable = false)
	Instant timestamp;

	@Column(name = "tx_hash", length = 32, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash txHash;

	@Column(name = "type", nullable = false, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = TransferTypeConverter.class)
	TransferType type;

	@Column(name = "from_address", length = 20, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = AddressConverter.class)
	Address from;

	@Column(name = "to_address", length = 20, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = AddressConverter.class)
	Address to;

	@Column(name = "token_address", length = 20, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = AddressConverter.class)
	Address tokenAddress;

	@Column(name = "amount", nullable = true, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
	@Convert(converter = WeiConverter.class)
	Wei amount;
}