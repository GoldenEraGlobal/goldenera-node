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
package global.goldenera.node.bridge.api.v1;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.bridge.api.v1.dtos.BridgeDeliveryDtoV1_Page;
import global.goldenera.node.bridge.api.v1.mappers.BridgeDeliveryMapper;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.bridge.services.BridgeDeliveryService;
import global.goldenera.node.bridge.services.BridgeDeliveryService.DeliveryFilter;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BRIDGE_MANAGE_SUBSCRIPTIONS')")
@RequestMapping("/api/bridge/v1/delivery")
@ConditionalOnProperty(prefix = "ge.general", name = "postgresql-enable", havingValue = "true")
public class BridgeDeliveryApiV1 {

    private final BridgeDeliveryService bridgeDeliveryService;
    private final BridgeDeliveryMapper mapper;

    @GetMapping("page")
    public BridgeDeliveryDtoV1_Page getPage(
            @RequestParam int pageNumber,
            @RequestParam int pageSize,
            @RequestParam(required = false) Sort.Direction direction,
            @RequestParam(required = false) UUID destinationId,
            @RequestParam(required = false) UUID deliveryId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) BridgeDeliveryState state,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant createdTo,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiKey apiKey)) {
            throw new GEValidationException("Invalid authentication");
        }
        return mapper.map(bridgeDeliveryService.getPage(pageNumber, pageSize, direction,
                new DeliveryFilter(destinationId, deliveryId, eventId, state, createdFrom, createdTo), apiKey));
    }
}
