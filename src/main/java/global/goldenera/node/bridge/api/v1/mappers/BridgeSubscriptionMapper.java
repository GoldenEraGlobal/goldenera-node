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
package global.goldenera.node.bridge.api.v1.mappers;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscriptionDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeSubscriptionDtoV1_Page;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BridgeSubscriptionMapper {

    private final GeneralProperties generalProperties;

    public BridgeSubscriptionDtoV1 map(BridgeSubscription in) {
        return new BridgeSubscriptionDtoV1(in.getId().toString(), in.getDestination().getId().toString(), in.getAddress().toChecksumAddress(),
                in.getDestination().getUrl(), in.isEnabled(), in.getCreatedAt());
    }

    public BridgeSubscriptionDtoV1_Page map(Page<BridgeSubscription> in) {
        return new BridgeSubscriptionDtoV1_Page(generalProperties.getNetwork(), in.getContent().stream().map(this::map).toList(),
                in.getTotalPages(), in.getTotalElements());
    }
}
