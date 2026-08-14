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
package global.goldenera.node.core.storage.chainidentity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Stable, strict binary encoding used only for the RocksDB metadata value. */
final class StoredChainIdentityCodec {

	private static final int MAGIC = 0x47454349; // GECI
	private static final int HASH_BYTES = 32;
	private static final int MAX_ENCODED_BYTES = 4 + 4 + 4 + 2
			+ StoredChainIdentity.MAX_CHAIN_ID_BYTES + HASH_BYTES + 1 + HASH_BYTES;

	private StoredChainIdentityCodec() {
	}

	static byte[] encode(StoredChainIdentity identity) {
		byte[] chainId = identity.chainId().getBytes(StandardCharsets.UTF_8);
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(identity.formatVersion());
			output.writeInt(identity.carrierNetworkCode());
			output.writeShort(chainId.length);
			output.write(chainId);
			output.write(hexToBytes(identity.genesisHash().substring(2)));
			output.writeBoolean(identity.manifestFingerprint() != null);
			if (identity.manifestFingerprint() != null) {
				output.write(hexToBytes(identity.manifestFingerprint()));
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to encode chain identity", e);
		}
	}

	static StoredChainIdentity decode(byte[] encoded) {
		if (encoded == null || encoded.length > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("Invalid chain identity metadata length");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (input.readInt() != MAGIC) {
				throw new IllegalArgumentException("Invalid chain identity metadata magic");
			}
			int formatVersion = input.readInt();
			int carrierNetworkCode = input.readInt();
			int chainIdLength = input.readUnsignedShort();
			if (chainIdLength == 0 || chainIdLength > StoredChainIdentity.MAX_CHAIN_ID_BYTES) {
				throw new IllegalArgumentException("Invalid persisted chain ID length");
			}
			String chainId = decodeUtf8(input.readNBytes(chainIdLength), chainIdLength);
			String genesisHash = "0x" + HexFormat.of().formatHex(readExactly(input, HASH_BYTES));
			boolean hasFingerprint = input.readBoolean();
			String fingerprint = hasFingerprint
					? HexFormat.of().formatHex(readExactly(input, HASH_BYTES))
					: null;
			if (input.available() != 0) {
				throw new IllegalArgumentException("Trailing bytes in chain identity metadata");
			}
			return new StoredChainIdentity(formatVersion, carrierNetworkCode, chainId, genesisHash, fingerprint);
		} catch (EOFException e) {
			throw new IllegalArgumentException("Truncated chain identity metadata", e);
		} catch (IOException e) {
			throw new IllegalArgumentException("Failed to decode chain identity metadata", e);
		}
	}

	private static byte[] readExactly(DataInputStream input, int length) throws IOException {
		byte[] value = input.readNBytes(length);
		if (value.length != length) {
			throw new EOFException("Expected " + length + " bytes");
		}
		return value;
	}

	private static String decodeUtf8(byte[] encoded, int expectedLength) {
		if (encoded.length != expectedLength) {
			throw new IllegalArgumentException("Truncated persisted chain ID");
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(encoded))
					.toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("Persisted chain ID is not valid UTF-8", e);
		}
	}

	private static byte[] hexToBytes(String value) {
		return HexFormat.of().parseHex(value);
	}
}
