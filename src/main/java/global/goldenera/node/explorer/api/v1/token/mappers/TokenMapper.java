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
package global.goldenera.node.explorer.api.v1.token.mappers;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1;
import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1_Page;
import global.goldenera.node.explorer.entities.ExToken;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TokenMapper {

    public TokenDtoV1 map(
            @NonNull ExToken in) {
        return new TokenDtoV1(
                in.getVersion(),
                in.getAddress(),
                in.getName(),
                in.getSmallestUnitName(),
                in.getNumberOfDecimals(),
                in.getWebsiteUrl(),
                in.getLogoUrl(),
                in.getMaxSupply(),
                in.isUserBurnable(),
                in.getOriginTxHash(),
                in.getUpdatedByTxHash(),
                in.getTotalSupply(),
                in.getCreatedAtBlockHeight(),
                in.getUpdatedAtBlockHeight(),
                in.getCreatedAtTimestamp(),
                in.getUpdatedAtTimestamp());
    }

    public List<TokenDtoV1> map(
            @NonNull List<ExToken> in) {
        return in.stream().map(this::map).toList();
    }

    public TokenDtoV1_Page map(
            @NonNull Page<ExToken> in) {
        return new TokenDtoV1_Page(
                map(in.toList()),
                in.getTotalPages(),
                in.getTotalElements());
    }

}
