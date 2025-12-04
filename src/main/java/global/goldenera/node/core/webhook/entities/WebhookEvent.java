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
package global.goldenera.node.core.webhook.entities;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.Type;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.enums.WebhookEventType;
import global.goldenera.node.core.webhook.converters.WebhookEventTypeConverter;
import global.goldenera.node.shared.converters.AddressConverter;
import io.hypersistence.utils.hibernate.type.json.JsonType;
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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "webhook_event", indexes = {
		@Index(name = "idx_webhook_event_webhook_id", columnList = "webhook_id"),
		@Index(name = "idx_webhook_event_type", columnList = "type"),
		@Index(name = "idx_webhook_event_address", columnList = "address_filter"),
		@Index(name = "idx_webhook_event_token_address", columnList = "token_address_filter")
})
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
@EqualsAndHashCode(of = "id")
public class WebhookEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "webhook_event_seq_gen")
	@SequenceGenerator(name = "webhook_event_seq_gen", sequenceName = "webhook_event_id_seq", allocationSize = 1000)
	@Column(name = "id", unique = true, nullable = false, updatable = false)
	Long id;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "webhook_id", referencedColumnName = "id", nullable = false, updatable = false)
	Webhook webhook;

	@Column(name = "type", nullable = false)
	@Convert(converter = WebhookEventTypeConverter.class)
	WebhookEventType type;

	@Column(name = "address_filter", nullable = true, columnDefinition = "BYTEA")
	@Convert(converter = AddressConverter.class)
	Address addressFilter;

	@Column(name = "token_address_filter", nullable = true, columnDefinition = "BYTEA")
	@Convert(converter = AddressConverter.class)
	Address tokenAddressFilter;

	@Type(JsonType.class)
	@Column(name = "criteria", columnDefinition = "jsonb", nullable = true)
	Map<String, Object> criteria;

	@Column(name = "created_at", nullable = false, updatable = false)
	Instant createdAt;

	public WebhookEvent(Webhook webhook, WebhookEventType type,
			Address addressFilter, Address tokenAddressFilter, Map<String, Object> criteria) {
		this.webhook = webhook;
		this.type = type;
		this.addressFilter = addressFilter;
		this.tokenAddressFilter = tokenAddressFilter;
		this.criteria = criteria;
		this.createdAt = Instant.now();
	}
}