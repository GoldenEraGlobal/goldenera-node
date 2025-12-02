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
import java.util.LinkedHashSet;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadDecoder;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.explorer.converters.AddressSetConverter;
import global.goldenera.node.explorer.converters.BipStatusConverter;
import global.goldenera.node.explorer.converters.BipTypeConverter;
import global.goldenera.node.explorer.converters.TxVersionConverter;
import global.goldenera.node.explorer.converters.state.BipStateMetadataVersionConverter;
import global.goldenera.node.explorer.converters.state.BipStateVersionConverter;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.BipStateMetadata;
import global.goldenera.node.shared.converters.AddressConverter;
import global.goldenera.node.shared.converters.BytesConverter;
import global.goldenera.node.shared.converters.HashConverter;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
import global.goldenera.node.shared.enums.state.BipStateMetadataVersion;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
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
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "explorer_bip_state", indexes = {
		@Index(name = "idx_explorer_bip_state_status", columnList = "status"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "bipHash")
@IdClass(ExBipState.BipStatePK.class)
public class ExBipState implements BipState {

	@Id
	@Column(name = "bip_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash bipHash; // PK

	@Column(name = "bip_state_version", nullable = false, columnDefinition = "INTEGER")
	@Convert(converter = BipStateVersionConverter.class)
	BipStateVersion version;

	@Column(name = "status", nullable = false, columnDefinition = "INTEGER")
	@NonNull
	@Convert(converter = BipStatusConverter.class)
	BipStatus status;

	@Column(name = "is_action_executed", nullable = false)
	boolean isActionExecuted;

	@Column(name = "type", nullable = false, columnDefinition = "INTEGER")
	@NonNull
	@Convert(converter = BipTypeConverter.class)
	BipType type;

	@Column(name = "number_of_required_votes", nullable = false)
	long numberOfRequiredVotes;

	@Column(name = "approvers", columnDefinition = "BYTEA")
	@Convert(converter = AddressSetConverter.class)
	LinkedHashSet<Address> approvers;

	@Column(name = "disapprovers", columnDefinition = "BYTEA")
	@Convert(converter = AddressSetConverter.class)
	LinkedHashSet<Address> disapprovers;

	@Column(name = "expiration_timestamp", nullable = false)
	Instant expirationTimestamp;

	// --- Metadata (Embedded) ---
	@Embedded
	ExBipMetadata metadata;

	// --- Timestamps ---

	@Column(name = "executed_at_timestamp", nullable = true)
	Instant executedAtTimestamp;

	@Column(name = "origin_tx_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash originTxHash;

	@Column(name = "updated_by_tx_hash", length = 32, nullable = true, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash updatedByTxHash;

	@Column(name = "created_at_block_height", nullable = false, updatable = false)
	long createdAtBlockHeight;

	@Column(name = "created_at_timestamp", nullable = false, updatable = false)
	Instant createdAtTimestamp;

	@Column(name = "updated_at_block_height", nullable = false, updatable = true)
	long updatedAtBlockHeight;

	@Column(name = "updated_at_timestamp", nullable = false, updatable = true)
	Instant updatedAtTimestamp;

	// --- Lifecycle Callbacks (Triggered by Entity Manager) ---

	@PostLoad
	public void onLoad() {
		if (metadata != null) {
			metadata.ensurePayloadDecoded();
		}
	}

	@PrePersist
	@PreUpdate
	public void onSave() {
		if (metadata != null) {
			metadata.ensurePayloadEncoded();
		}
	}

	// --- PK Class ---
	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	@Getter
	@Setter
	public static class BipStatePK implements Serializable {
		@Column(name = "bip_hash", length = 32, nullable = false, updatable = false, columnDefinition = "BYTEA")
		@Convert(converter = HashConverter.class)
		Hash bipHash;
	}

	// --- Embedded Metadata Class ---
	@Embeddable
	@Getter
	@Setter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@FieldDefaults(level = PRIVATE)
	public static class ExBipMetadata implements BipStateMetadata {

		@Column(name = "meta_version", columnDefinition = "INTEGER")
		@Convert(converter = BipStateMetadataVersionConverter.class)
		BipStateMetadataVersion version;

		@Column(name = "meta_tx_version", columnDefinition = "INTEGER")
		@Convert(converter = TxVersionConverter.class)
		TxVersion txVersion;

		@Column(name = "meta_derived_token_address", length = 20, columnDefinition = "BYTEA")
		@Convert(converter = AddressConverter.class)
		Address derivedTokenAddress;

		@Column(name = "meta_tx_payload_raw", columnDefinition = "BYTEA")
		@Convert(converter = BytesConverter.class)
		Bytes rawPayload;

		@Transient
		TxPayload txPayload;

		public TxPayload getPayload() {
			if (this.txPayload == null) {
				ensurePayloadDecoded();
			}
			return this.txPayload;
		}

		public void ensurePayloadDecoded() {
			if (txPayload == null && rawPayload != null && !rawPayload.isEmpty()) {
				this.txPayload = TxPayloadDecoder.INSTANCE.decode(rawPayload, txVersion);
			}
		}

		public void ensurePayloadEncoded() {
			if (txPayload != null) {
				this.rawPayload = TxPayloadEncoder.INSTANCE.encode(txPayload, txVersion);
			}
		}

		@Override
		public TxPayload getTxPayload() {
			ensurePayloadDecoded();
			return txPayload;
		}
	}
}