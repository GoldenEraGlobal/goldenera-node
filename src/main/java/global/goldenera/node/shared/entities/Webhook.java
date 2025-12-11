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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.hibernate.annotations.Type;

import global.goldenera.node.shared.converters.BytesConverter;
import global.goldenera.node.shared.converters.WebhookTypeConverter;
import global.goldenera.node.shared.enums.WebhookType;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "webhook", indexes = {
		@Index(name = "idx_webhook_label", columnList = "label", unique = true),
		@Index(name = "idx_webhook_created_by_api_key_id", columnList = "created_by_api_key_id")
})
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "id")
public class Webhook {

	@OneToMany(mappedBy = "webhook", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	List<WebhookEvent> events = new ArrayList<>();

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
	UUID id;

	@Version
	@Column(name = "version", nullable = false, updatable = true)
	Long version;

	@Column(name = "type", nullable = false, updatable = false)
	@Convert(converter = WebhookTypeConverter.class)
	WebhookType type;

	@Column(name = "dto_version", nullable = true, updatable = true)
	Integer dtoVersion;

	@Column(name = "label", nullable = false, updatable = true, length = 32)
	String label;

	@Column(name = "description", nullable = true, updatable = true, length = 255)
	String description;

	@Column(name = "url", nullable = false, updatable = false, length = 2048)
	String url;

	@Column(name = "secret_key", nullable = false, updatable = false, columnDefinition = "BYTEA")
	@Convert(converter = BytesConverter.class)
	Bytes secretKey;

	@Column(name = "enabled", nullable = false, updatable = true)
	boolean enabled;

	@Column(name = "created_at", nullable = false, updatable = false)
	Instant createdAt;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_api_key_id", referencedColumnName = "id", nullable = false, updatable = false)
	ApiKey createdByApiKey;

	@Type(JsonType.class)
	@Column(name = "query_params", columnDefinition = "jsonb", nullable = true, updatable = true)
	Map<String, Object> queryParams;

	@Type(JsonType.class)
	@Column(name = "headers", columnDefinition = "jsonb", nullable = true, updatable = true)
	Map<String, Object> headers;

	public Webhook(WebhookType type, Integer dtoVersion, String label, String description, String url, Bytes secretKey,
			ApiKey createdByApiKey,
			Map<String, Object> queryParams, Map<String, Object> headers) {
		this.type = type;
		this.dtoVersion = dtoVersion;
		this.label = label;
		this.description = description;
		this.url = url;
		this.secretKey = secretKey;
		this.enabled = true;
		this.createdAt = Instant.now();
		this.createdByApiKey = createdByApiKey;
		this.queryParams = queryParams;
		this.headers = headers;
	}

}
