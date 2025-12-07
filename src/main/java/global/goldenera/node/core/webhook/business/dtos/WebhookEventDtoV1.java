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
package global.goldenera.node.core.webhook.business.dtos;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainBlockHeaderDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainTxDtoV1;
import global.goldenera.node.core.enums.WebhookEventType;
import global.goldenera.node.core.enums.WebhookTxStatus;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
                @JsonSubTypes.Type(value = WebhookEventDtoV1.NewBlockEvent.class, name = "NEW_BLOCK"),
                @JsonSubTypes.Type(value = WebhookEventDtoV1.AddressActivityEvent.class, name = "ADDRESS_ACTIVITY")
})
public sealed interface WebhookEventDtoV1 {

        WebhookEventType type();

        // --- 1. NEW BLOCK ---

        record NewBlockEvent(
                        WebhookEventType type,
                        BlockchainBlockHeaderDtoV1 data) implements WebhookEventDtoV1 {
        }

        // --- 2. ADDRESS ACTIVITY ---

        record AddressActivityEvent(
                        WebhookEventType type,
                        BlockchainTxDtoV1 data,
                        WebhookTxStatus status) implements WebhookEventDtoV1 {
        }
}