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
package global.goldenera.node.admin.api.v1.bridgesubscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.admin.api.v1.bridgesubscription.dtos.AdminBridgeSubscriptionDtoV1_Page;
import global.goldenera.node.admin.api.v1.bridgesubscription.mappers.AdminBridgeSubscriptionMapper;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.services.BridgeSubscriptionService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/v1/bridge-subscription")
@ConditionalOnProperty(prefix = "ge.general", name = "postgresql-enable", havingValue = "true")
public class AdminBridgeSubscriptionApiV1 {

    private final BridgeSubscriptionService bridgeSubscriptionService;
    private final AdminBridgeSubscriptionMapper mapper;

    @GetMapping("page")
    public AdminBridgeSubscriptionDtoV1_Page getPage(
            @RequestParam int pageNumber,
            @RequestParam int pageSize,
            @RequestParam(required = false) Sort.Direction direction,
            @RequestParam(required = false) Long apiKeyId,
            @RequestParam(required = false) Address address,
            @RequestParam(defaultValue = "ACTIVE") BridgeSubscriptionStatus status) {
        return mapper.map(bridgeSubscriptionService.getAdminPage(pageNumber, pageSize, direction, apiKeyId, address, status));
    }
}
