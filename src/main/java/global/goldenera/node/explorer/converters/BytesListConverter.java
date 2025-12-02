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
package global.goldenera.node.explorer.converters;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class BytesListConverter implements AttributeConverter<List<Bytes>, byte[]> {

	private static final int VERSION = 1;

	@Override
	public byte[] convertToDatabaseColumn(List<Bytes> attribute) {
		if (attribute == null) {
			return null;
		}

		return RLP.encode((out) -> {
			out.startList();
			out.writeIntScalar(VERSION);
			out.writeList(attribute, (b, out2) -> out2.writeBytes(b));
			out.endList();
		}).toArray();
	}

	@Override
	public List<Bytes> convertToEntityAttribute(byte[] dbData) {
		if (dbData == null) {
			return null;
		}

		RLPInput input = RLP.input(Bytes.wrap(dbData));
		int fields = input.enterList();
		if (fields < 1) {
			throw new GEFailedException("Version field is missing");
		}
		int version = input.readIntScalar();
		if (version != 1) {
			throw new GEFailedException("Unsupported version: " + version);
		}
		List<Bytes> result = input.readList((in) -> in.readBytes());
		input.leaveList();
		return result;
	}
}