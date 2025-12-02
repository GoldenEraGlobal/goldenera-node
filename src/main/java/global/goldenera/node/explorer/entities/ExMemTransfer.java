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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxPayloadType;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadDecoder;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.explorer.converters.NetworkConverter;
import global.goldenera.node.explorer.converters.TransferTypeConverter;
import global.goldenera.node.explorer.converters.TxPayloadTypeConverter;
import global.goldenera.node.explorer.converters.TxTypeConverter;
import global.goldenera.node.explorer.converters.TxVersionConverter;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.BytesConverter;
import global.goldenera.node.shared.converters.HashConverter;
import global.goldenera.node.shared.converters.SignatureConverter;
import global.goldenera.node.shared.converters.WeiConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_mem_transfer", indexes = {
		@Index(name = "idx_mem_tx_added", columnList = "added_at DESC"),
		@Index(name = "idx_mem_tx_sender", columnList = "from_address"),
		@Index(name = "idx_mem_tx_recipient", columnList = "to_address"),
		@Index(name = "idx_mem_tx_fee", columnList = "fee DESC")
})
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@IdClass(ExMemTransfer.ExMemTransferPK.class)
public class ExMemTransfer implements Tx {

	@Id
	@Column(name = "hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash hash;

	// --- META DATA (Mempool specific) ---

	@Column(name = "added_at", nullable = false, updatable = false)
	Instant addedAt;

	// --- UI DATA (High Level View) ---

	@Column(name = "transfer_type", nullable = false, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = TransferTypeConverter.class)
	TransferType transferType;

	@Column(name = "from_address", length = 20, nullable = false, updatable = false, columnDefinition = "BYTEA")
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

	// --- RAW DATA (Low Level ExTx fields) ---

	@Column(name = "tx_type", nullable = false, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = TxTypeConverter.class)
	TxType txType;

	@Column(name = "tx_timestamp", nullable = false, updatable = false)
	Instant txTimestamp;

	@Column(name = "network", nullable = false, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = NetworkConverter.class)
	Network network;

	@Column(name = "version", nullable = false, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = TxVersionConverter.class)
	TxVersion version;

	@Column(name = "fee", nullable = false, updatable = false, precision = 80, scale = 0, columnDefinition = "NUMERIC")
	@Convert(converter = WeiConverter.class)
	Wei fee;

	@Column(name = "nonce", nullable = false, updatable = false)
	Long nonce;

	@Column(name = "tx_size", nullable = false, updatable = false)
	int size;

	@Column(name = "signature", length = 65, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = SignatureConverter.class)
	Signature signature;

	@Column(name = "reference_hash", length = 32, nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash referenceHash;

	@Column(name = "message", nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes message;

	// --- PAYLOAD HANDLING ---

	@Column(name = "payload_type", nullable = true, updatable = false, columnDefinition = "INTEGER")
	@Convert(converter = TxPayloadTypeConverter.class)
	TxPayloadType payloadType;

	@Column(name = "raw_payload", nullable = true, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes rawPayload;

	@Transient
	TxPayload payload;

	public TxPayload getPayload() {
		if (this.payload == null) {
			ensurePayloadDecoded();
		}
		return this.payload;
	}

	@PostLoad
	public void onLoad() {
		ensurePayloadDecoded();
	}

	@PrePersist
	@PreUpdate
	public void onSave() {
		ensurePayloadEncoded();
	}

	private void ensurePayloadDecoded() {
		if (payload == null && rawPayload != null && !rawPayload.isEmpty()) {
			this.payload = TxPayloadDecoder.INSTANCE.decode(rawPayload, version);
		}
	}

	private void ensurePayloadEncoded() {
		if (payload != null) {
			this.rawPayload = TxPayloadEncoder.INSTANCE.encode(payload, version);
			this.payloadType = payload.getPayloadType();
		}
	}

	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	@Getter
	@Setter
	public static class ExMemTransferPK implements Serializable {
		@Column(name = "hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
		@Convert(converter = HashConverter.class)
		Hash hash;
	}

	@Override
	public Address getSender() {
		return from;
	}

	@Override
	public Instant getTimestamp() {
		return txTimestamp;
	}

	@Override
	public TxType getType() {
		return txType;
	}

	@Override
	public Address getRecipient() {
		return to;
	}
}