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
package global.goldenera.node.shared.entities;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.shared.converters.ApiKeyPermissionConverter;
import global.goldenera.node.shared.converters.BytesConverter;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "api_key", indexes = {
		@Index(name = "idx_apikey_prefix", columnList = "key_prefix", unique = true),
		@Index(name = "idx_apikey_label", columnList = "label", unique = false)
})
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "id")
public class ApiKey {

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "api_key_permission", joinColumns = @JoinColumn(name = "api_key_id"))
	@Column(name = "permission", nullable = false, columnDefinition = "integer")
	@Convert(converter = ApiKeyPermissionConverter.class)
	Set<ApiKeyPermission> permissions = new HashSet<>();

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "api_key_seq_gen")
	@SequenceGenerator(name = "api_key_seq_gen", sequenceName = "api_key_id_seq", allocationSize = 5)
	@Column(name = "id", unique = true, nullable = false, updatable = false)
	Long id;

	@Version
	@Column(name = "version", nullable = false, updatable = true)
	Long version;

	@Column(name = "label", nullable = false, updatable = true, length = 32)
	String label;

	@Column(name = "description", nullable = true, updatable = true, length = 255)
	String description;

	@Column(name = "key_prefix", nullable = false, unique = true, length = 255)
	String keyPrefix;

	@Column(name = "secret_key", nullable = false, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes secretKey;

	@Column(name = "enabled", nullable = false)
	boolean enabled;

	@Column(name = "max_webhooks", nullable = true, updatable = true)
	Long maxWebhooks;

	@Column(name = "expires_at", nullable = true, updatable = true)
	Instant expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	Instant createdAt;

	public ApiKey(
			@NonNull Set<ApiKeyPermission> permissions,
			@NonNull String label,
			String description,
			@NonNull String keyPrefix,
			@NonNull Bytes secretKey,
			boolean enabled,
			Long maxWebhooks,
			Instant expiresAt) {
		this.permissions = permissions;
		this.label = label;
		this.description = description;
		this.keyPrefix = keyPrefix;
		this.secretKey = secretKey;
		this.enabled = enabled;
		this.maxWebhooks = maxWebhooks;
		this.expiresAt = expiresAt;
		this.createdAt = Instant.now();
	}

	public boolean isExpired() {
		if (expiresAt == null) {
			return false;
		}
		return Instant.now().isAfter(expiresAt);
	}

	public boolean hasPermission(ApiKeyPermission permission) {
		return permissions.contains(permission);
	}
}
