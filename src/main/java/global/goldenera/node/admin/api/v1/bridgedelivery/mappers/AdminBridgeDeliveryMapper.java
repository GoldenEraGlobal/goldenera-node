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
package global.goldenera.node.admin.api.v1.bridgedelivery.mappers;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.node.admin.api.v1.bridgedelivery.dtos.AdminBridgeDeliveryDtoV1;
import global.goldenera.node.admin.api.v1.bridgedelivery.dtos.AdminBridgeDeliveryDtoV1_Page;
import global.goldenera.node.bridge.entities.BridgeDelivery;

@Component
public class AdminBridgeDeliveryMapper {

    public AdminBridgeDeliveryDtoV1 map(BridgeDelivery in) {
        return new AdminBridgeDeliveryDtoV1(in.getDeliveryId().toString(), in.getEventId().toString(), in.getDestination().getId().toString(),
                in.getDestination().getUrl(), in.getState(), in.getAttempts(), in.getLastHttpStatus(), in.getLastError(),
                in.getNextAttemptAt(), in.getCreatedAt(), in.getUpdatedAt(), in.getBody(), in.getDestination().getCreatedByApiKey().getId());
    }

    public AdminBridgeDeliveryDtoV1_Page map(Page<BridgeDelivery> in) {
        return new AdminBridgeDeliveryDtoV1_Page(in.getContent().stream().map(this::map).toList(),
                in.getTotalPages(), in.getTotalElements());
    }
}
