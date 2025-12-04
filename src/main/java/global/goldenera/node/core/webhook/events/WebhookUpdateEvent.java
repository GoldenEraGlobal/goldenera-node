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
package global.goldenera.node.core.webhook.events;

import static lombok.AccessLevel.PRIVATE;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEvent;

import global.goldenera.node.core.webhook.entities.Webhook;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebhookUpdateEvent extends ApplicationEvent {

	@NonNull
	UpdateType type;

	@NonNull
	UUID webhookId;

	Optional<Webhook> webhook;

	public WebhookUpdateEvent(Object source, @NonNull UpdateType type, @NonNull UUID webhookId,
			Optional<Webhook> webhook) {
		super(source);
		this.type = type;
		this.webhookId = webhookId;
		this.webhook = webhook;
	}

	public static enum UpdateType {
		CREATE_WEBHOOK, UPDATE_WEBHOOK, DELETE_WEBHOOK;
	}
}