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

import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.hibernate.annotations.Type;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.converters.EntityTypeConverter;
import global.goldenera.node.explorer.converters.OperationTypeConverter;
import global.goldenera.node.explorer.enums.EntityType;
import global.goldenera.node.explorer.enums.OperationType;
import global.goldenera.node.shared.converters.BytesConverter;
import global.goldenera.node.shared.converters.HashConverter;
import io.hypersistence.utils.hibernate.type.json.JsonType;
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
@Table(name = "explorer_revert_log", indexes = {
		@Index(name = "idx_revert_block_hash", columnList = "block_hash"),
		@Index(name = "idx_revert_height", columnList = "block_height")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = PRIVATE)
public class ExRevertLog {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revert_log_seq_gen")
	@SequenceGenerator(name = "revert_log_seq_gen", sequenceName = "explorer_revert_log_seq", allocationSize = 1)
	Long id;

	// --- Partition Key ---
	@Column(name = "block_height", nullable = false, updatable = false)
	long blockHeight;

	@Column(name = "block_hash", nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = HashConverter.class)
	Hash blockHash;

	@Column(name = "entity_type", length = 30, nullable = false, columnDefinition = "INTEGER")
	@Convert(converter = EntityTypeConverter.class)
	EntityType entityType;

	@Column(name = "operation_type", length = 10, nullable = false, columnDefinition = "INTEGER")
	@Convert(converter = OperationTypeConverter.class)
	OperationType operationType;

	// --- Generic Search Keys (BYTEA) ---
	// For AccountBalance: key1=Address, key2=TokenAddress
	// For Nonce/Authority: key1=Address, key2=null
	// For BIP: key1=OriginTxHash, key2=null
	// For Token: key1=TokenAddress, key2=null
	// For AddressAlias: key1=Alias(UTF8 bytes), key2=null

	@Column(name = "ref_key_1", nullable = false, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes refKey1;

	@Column(name = "ref_key_2", nullable = true, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes refKey2;

	@Type(JsonType.class)
	@Column(name = "old_value", columnDefinition = "jsonb")
	Map<String, Object> oldValue;
}