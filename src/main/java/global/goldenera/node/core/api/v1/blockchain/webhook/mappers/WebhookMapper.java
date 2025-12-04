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
package global.goldenera.node.core.api.v1.blockchain.webhook.mappers;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookDtoV1_Page;
import global.goldenera.node.core.webhook.business.WebhookService;
import global.goldenera.node.core.webhook.entities.Webhook;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WebhookMapper {

    public WebhookDtoV1.CreatedWebhookDtoV1 map(@NonNull WebhookService.CreatedWebhook in) {
        return new WebhookDtoV1.CreatedWebhookDtoV1(
                map(in.getWebhook()),
                in.getSecretKey());
    }

    public WebhookDtoV1 map(@NonNull Webhook in) {
        return new WebhookDtoV1(
                in.getId().toString(),
                in.getLabel(),
                in.getDescription(),
                in.getUrl(),
                in.isEnabled(),
                in.getCreatedAt(),
                in.getCreatedByApiKey().getId(),
                in.getQueryParams(),
                in.getHeaders());
    }

    public List<WebhookDtoV1> map(@NonNull List<Webhook> in) {
        return in.stream()
                .map(this::map)
                .toList();
    }

    public WebhookDtoV1_Page map(@NonNull Page<Webhook> in) {
        return new WebhookDtoV1_Page(
                map(in.toList()),
                in.getTotalPages(),
                in.getTotalElements());
    }

}
