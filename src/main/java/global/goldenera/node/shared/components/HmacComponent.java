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
package global.goldenera.node.shared.components;

import static lombok.AccessLevel.PRIVATE;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Component;

import global.goldenera.node.shared.exceptions.GERuntimeException;
import global.goldenera.node.shared.properties.SecurityProperties;
import lombok.experimental.FieldDefaults;

@Component
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class HmacComponent {

	static final String ALGORITHM = "HmacSHA256";

	SecretKeySpec secretKeySpec;

	public HmacComponent(SecurityProperties securityConfig) {
		String secret = securityConfig.getHmacSecret();
		byte[] keyBytes = Base64.getDecoder().decode(secret);

		// Init secret key spec
		this.secretKeySpec = new SecretKeySpec(
				keyBytes,
				ALGORITHM);
	}

	/**
	 * Create HMAC-SHA256 hash from data and return it as Base64 string.
	 */
	public Bytes hash(Bytes data) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(secretKeySpec);
			byte[] hashBytes = mac.doFinal(data.toArray());
			return Bytes.wrap(hashBytes);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new GERuntimeException("Error creating HMAC hash", e);
		}
	}

	/**
	 * Securely compare two strings (e.g. two hashes) in constant time,
	 * to prevent "Timing Attacks".
	 */
	public boolean secureCompare(Bytes a, Bytes b) {
		return MessageDigest.isEqual(
				a.toArray(),
				b.toArray());
	}
}