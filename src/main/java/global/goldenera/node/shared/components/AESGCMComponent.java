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

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Component;

import global.goldenera.node.shared.exceptions.GERuntimeException;
import global.goldenera.node.shared.properties.SecurityProperties;
import lombok.experimental.FieldDefaults;

@Component
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class AESGCMComponent {

	static final String ALGORITHM = "AES/GCM/NoPadding";
	static final int GCM_IV_LENGTH_BYTES = 12;
	static final int GCM_TAG_LENGTH_BITS = 128;

	SecureRandom random;
	SecretKeySpec secretKeySpec;

	public AESGCMComponent(SecurityProperties securityProperties) {
		random = new SecureRandom();
		String secret = securityProperties.getAesGcmSecret();
		byte[] keyBytes = Base64.getDecoder().decode(secret);
		this.secretKeySpec = new SecretKeySpec(keyBytes, "AES");
	}

	/**
	 * Encrypt data with AES-256-GCM
	 *
	 * @param data
	 *            Original data to encrypt
	 * @return Bytes containing [IV + encrypted data]
	 */
	public Bytes encrypt(Bytes data) {
		try {
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			random.nextBytes(iv);
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, this.secretKeySpec, gcmSpec);

			byte[] ciphertext = cipher.doFinal(data.toArray());

			ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
			byteBuffer.put(iv);
			byteBuffer.put(ciphertext);
			return Bytes.wrap(byteBuffer.array());
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
				| InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
			throw new GERuntimeException("Error encrypting data with AES-256-GCM! " + e.getMessage(), e);
		}
	}

	/**
	 * Decrypt data with AES-256-GCM
	 *
	 * @param encryptedData
	 *            Bytes containing [IV + encrypted data]
	 * @return Original data
	 */
	public Bytes decrypt(Bytes encryptedData) {
		try {
			byte[] combined = encryptedData.toArray();
			ByteBuffer byteBuffer = ByteBuffer.wrap(combined);
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			byteBuffer.get(iv);

			byte[] ciphertext = new byte[byteBuffer.remaining()];
			byteBuffer.get(ciphertext);
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, this.secretKeySpec, gcmSpec);

			byte[] plainData = cipher.doFinal(ciphertext);
			return Bytes.wrap(plainData);
		} catch (Exception e) {
			throw new GERuntimeException("Error decrypting data with AES-256-GCM! " + e.getMessage(), e);
		}
	}
}