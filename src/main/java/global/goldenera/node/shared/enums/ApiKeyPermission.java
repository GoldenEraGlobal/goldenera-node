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

import org.springframework.security.core.GrantedAuthority;

import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public enum ApiKeyPermission implements GrantedAuthority {
	READ_ACCOUNT(0), READ_ADDRESS_ALIAS(1), READ_AUTHORITY(
			2), READ_BIP_STATE(3), READ_BLOCK_HEADER(4), READ_NETWORK_PARAMS(
					5), READ_MEMPOOL_TX(6), READ_TOKEN(
							7), READ_TX(8), READ_WRITE_WEBHOOK(
									9), READ_NODE_METRICS(
											10), SUBMIT_MEMPOOL_TX(11), READ_VALIDATOR(12), READ_SEARCH(13);

	int code;

	public static ApiKeyPermission fromCode(int code) {
		for (ApiKeyPermission permission : values()) {
			if (permission.getCode() == code) {
				return permission;
			}
		}
		throw new GEFailedException("Failed to get ApiKeyPermission from code: " + code);
	}

	@Override
	public String getAuthority() {
		return this.name();
	}
}
