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
package global.goldenera.node.explorer.api.v1.networkparams;

import static lombok.AccessLevel.PRIVATE;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.explorer.api.v1.networkparams.dtos.NetworkParamsDtoV1;
import global.goldenera.node.explorer.api.v1.networkparams.mappers.NetworkParamsMapper;
import global.goldenera.node.explorer.services.core.ExNetworkParamsCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.ExplorerApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@ExplorerApiSecurity(ApiKeyPermission.READ_NETWORK_PARAMS)
@RequestMapping(value = "api/explorer/v1/network-params")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class NetworkParamsApiV1 {

        ExNetworkParamsCoreService networkParamsCoreService;
        NetworkParamsMapper networkParamsMapper;

        @GetMapping
        public NetworkParamsDtoV1 apiV1NetworkParamsGet() {
                return networkParamsMapper.map(
                                networkParamsCoreService.getParams());
        }

}
