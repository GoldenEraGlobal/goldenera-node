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

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxInDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeTxDtoV1;
import global.goldenera.node.bridge.services.BridgeTxService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bridge/v1/tx")
public class BridgeTxApiV1 {

    private final BridgeTxService bridgeTxService;

    @GetMapping("by-hash/{txHash}")
    @PreAuthorize("hasAuthority('BRIDGE_READ_TX')")
    public BridgeTxDtoV1 getByHash(@PathVariable Hash txHash) {
        return bridgeTxService.getByHash(txHash);
    }

    @PostMapping("broadcast")
    @PreAuthorize("hasAuthority('BRIDGE_BROADCAST_TX')")
    public BridgeBroadcastTxDtoV1 broadcast(@RequestBody BridgeBroadcastTxInDtoV1 input) {
        return bridgeTxService.broadcast(input);
    }
}
