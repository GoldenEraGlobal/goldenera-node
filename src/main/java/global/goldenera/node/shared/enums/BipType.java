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
package global.goldenera.node.shared.enums;

import static lombok.AccessLevel.PRIVATE;

import global.goldenera.cryptoj.enums.TxPayloadType;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Getter
public enum BipType {
    UNKNOWN(-1), AUTHORITY_ADD(0), AUTHORITY_REMOVE(1), ADDRESS_ALIAS_ADD(2), ADDRESS_ALIAS_REMOVE(3), TOKEN_CREATE(
            4), TOKEN_UPDATE(5), TOKEN_MINT(6), TOKEN_BURN(7), NETWORK_PARAMS_SET(8);

    int code;

    public static BipType fromCode(int code) {
        for (BipType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new GEFailedException("Unknown BipType code: " + code + " in BipType.fromCode");
    }

    public static BipType fromTxPayloadType(@NonNull TxPayloadType txPayloadType) {
        switch (txPayloadType) {
            case BIP_TOKEN_CREATE:
                return BipType.TOKEN_CREATE;
            case BIP_TOKEN_UPDATE:
                return BipType.TOKEN_UPDATE;
            case BIP_TOKEN_MINT:
                return BipType.TOKEN_MINT;
            case BIP_TOKEN_BURN:
                return BipType.TOKEN_BURN;
            case BIP_NETWORK_PARAMS_SET:
                return BipType.NETWORK_PARAMS_SET;
            case BIP_AUTHORITY_ADD:
                return BipType.AUTHORITY_ADD;
            case BIP_AUTHORITY_REMOVE:
                return BipType.AUTHORITY_REMOVE;
            case BIP_ADDRESS_ALIAS_ADD:
                return BipType.ADDRESS_ALIAS_ADD;
            case BIP_ADDRESS_ALIAS_REMOVE:
                return BipType.ADDRESS_ALIAS_REMOVE;
            default:
                throw new GEFailedException(
                        "Unknown TxPayloadType: " + txPayloadType + " in BipType.fromTxPayloadType");
        }
    }
}
