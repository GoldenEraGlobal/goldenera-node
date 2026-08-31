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
package global.goldenera.node.explorer.snapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

final class ExplorerSnapshotChunkCodec {

	private static final int MAGIC = 0x47455831;
	private static final int MAX_COLUMNS = 128;
	private static final int MAX_CELL_BYTES = 16 * 1024 * 1024;

	private ExplorerSnapshotChunkCodec() {
	}

	static byte[] encodeHeader(ExplorerSnapshotTable table, List<ExplorerSnapshotColumn> columns, long rowCount)
			throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(ExplorerSnapshotManifest.FORMAT_VERSION);
			output.writeUTF(table.name());
			output.writeInt(ExplorerSnapshotTable.SCHEMA_VERSION);
			output.writeInt(columns.size());
			for (ExplorerSnapshotColumn column : columns) {
				output.writeUTF(column.name());
				output.writeByte(column.type().ordinal());
				output.writeBoolean(column.nullable());
			}
			output.writeLong(rowCount);
		}
		return bytes.toByteArray();
	}

	static byte[] encodeRow(ResultSet resultSet, List<ExplorerSnapshotColumn> columns) throws SQLException, IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			for (int index = 0; index < columns.size(); index++) {
				ExplorerSnapshotColumn column = columns.get(index);
				Object raw = resultSet.getObject(index + 1);
				if (raw == null) {
					output.writeBoolean(false);
					continue;
				}
				output.writeBoolean(true);
				switch (column.type()) {
					case BOOLEAN -> output.writeBoolean(resultSet.getBoolean(index + 1));
					case INT32 -> output.writeInt(resultSet.getInt(index + 1));
					case INT64 -> output.writeLong(resultSet.getLong(index + 1));
					case DECIMAL -> writeString(output, resultSet.getBigDecimal(index + 1).toPlainString());
					case STRING, JSON -> writeString(output, resultSet.getString(index + 1));
					case BYTES -> writeBytes(output, resultSet.getBytes(index + 1));
					case TIMESTAMP -> {
						Instant instant = resultSet.getTimestamp(index + 1).toInstant();
						output.writeLong(instant.getEpochSecond());
						output.writeInt(instant.getNano());
					}
				}
			}
		}
		if (bytes.size() > MAX_CELL_BYTES) {
			throw new ExplorerSnapshotException("Explorer snapshot row exceeds the 16 MiB safety limit");
		}
		return bytes.toByteArray();
	}

	static DecodedChunk decode(byte[] encoded, ExplorerSnapshotTable expectedTable) throws IOException {
		return decode(encoded, expectedTable, ExplorerSnapshotManifest.FORMAT_VERSION);
	}

	static void verifyFormatHeader(byte[] encoded, int manifestFormatVersion) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (input.readInt() != MAGIC
					|| !SnapshotFormatCompatibility.supportsExplorerChunkForManifest(
							manifestFormatVersion, input.readInt())) {
				throw new ExplorerSnapshotException("Invalid explorer snapshot chunk format header");
			}
		} catch (EOFException e) {
			throw new ExplorerSnapshotException("Truncated explorer snapshot chunk", e);
		}
	}

	static DecodedChunk decode(
			byte[] encoded, ExplorerSnapshotTable expectedTable, int manifestFormatVersion) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (input.readInt() != MAGIC
					|| !SnapshotFormatCompatibility.supportsExplorerChunkForManifest(
							manifestFormatVersion, input.readInt())) {
				throw new ExplorerSnapshotException("Invalid explorer snapshot chunk header");
			}
			ExplorerSnapshotTable table = ExplorerSnapshotTable.valueOf(input.readUTF());
			if (table != expectedTable || input.readInt() != ExplorerSnapshotTable.SCHEMA_VERSION) {
				throw new ExplorerSnapshotException("Explorer snapshot chunk table binding mismatch");
			}
			int columnCount = input.readInt();
			if (columnCount <= 0 || columnCount > MAX_COLUMNS) {
				throw new ExplorerSnapshotException("Invalid explorer snapshot column count: " + columnCount);
			}
			List<ExplorerSnapshotColumn> columns = new ArrayList<>(columnCount);
			for (int index = 0; index < columnCount; index++) {
				String name = input.readUTF();
				int typeOrdinal = input.readUnsignedByte();
				if (typeOrdinal >= ExplorerSnapshotValueType.values().length) {
					throw new ExplorerSnapshotException("Invalid explorer snapshot column type");
				}
				columns.add(new ExplorerSnapshotColumn(name, ExplorerSnapshotValueType.values()[typeOrdinal],
						input.readBoolean()));
			}
			long rowCount = input.readLong();
			if (rowCount < 0 || rowCount > Integer.MAX_VALUE) {
				throw new ExplorerSnapshotException("Invalid explorer snapshot row count: " + rowCount);
			}
			List<List<Object>> rows = new ArrayList<>((int) rowCount);
			for (long rowIndex = 0; rowIndex < rowCount; rowIndex++) {
				List<Object> row = new ArrayList<>(columnCount);
				for (ExplorerSnapshotColumn column : columns) {
					if (!input.readBoolean()) {
						if (!column.nullable()) {
							throw new ExplorerSnapshotException("Null in non-null explorer column " + column.name());
						}
						row.add(null);
						continue;
					}
					row.add(readValue(input, column.type()));
				}
				rows.add(Collections.unmodifiableList(row));
			}
			if (input.read() != -1) {
				throw new ExplorerSnapshotException("Trailing data in explorer snapshot chunk");
			}
			return new DecodedChunk(List.copyOf(columns), List.copyOf(rows));
		} catch (EOFException e) {
			throw new ExplorerSnapshotException("Truncated explorer snapshot chunk", e);
		}
	}

	static void bind(PreparedStatement statement, int index, ExplorerSnapshotColumn column, Object value)
			throws SQLException {
		if (value == null) {
			statement.setNull(index, sqlType(column.type()));
			return;
		}
		switch (column.type()) {
			case BOOLEAN -> statement.setBoolean(index, (Boolean) value);
			case INT32 -> statement.setInt(index, (Integer) value);
			case INT64 -> statement.setLong(index, (Long) value);
			case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) value);
			case STRING -> statement.setString(index, (String) value);
			case BYTES -> statement.setBytes(index, (byte[]) value);
			case TIMESTAMP -> statement.setTimestamp(index, Timestamp.from((Instant) value));
			case JSON -> statement.setObject(index, value, Types.OTHER);
		}
	}

	private static Object readValue(DataInputStream input, ExplorerSnapshotValueType type) throws IOException {
		return switch (type) {
			case BOOLEAN -> input.readBoolean();
			case INT32 -> input.readInt();
			case INT64 -> input.readLong();
			case DECIMAL -> new BigDecimal(readString(input));
			case STRING, JSON -> readString(input);
			case BYTES -> readBytes(input);
			case TIMESTAMP -> Instant.ofEpochSecond(input.readLong(), input.readInt());
		};
	}

	private static int sqlType(ExplorerSnapshotValueType type) {
		return switch (type) {
			case BOOLEAN -> Types.BOOLEAN;
			case INT32 -> Types.INTEGER;
			case INT64 -> Types.BIGINT;
			case DECIMAL -> Types.NUMERIC;
			case STRING -> Types.VARCHAR;
			case BYTES -> Types.VARBINARY;
			case TIMESTAMP -> Types.TIMESTAMP_WITH_TIMEZONE;
			case JSON -> Types.OTHER;
		};
	}

	private static void writeString(DataOutputStream output, String value) throws IOException {
		writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
	}

	private static String readString(DataInputStream input) throws IOException {
		return new String(readBytes(input), StandardCharsets.UTF_8);
	}

	private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
		if (value.length > MAX_CELL_BYTES) {
			throw new ExplorerSnapshotException("Explorer snapshot cell exceeds the 16 MiB safety limit");
		}
		output.writeInt(value.length);
		output.write(value);
	}

	private static byte[] readBytes(DataInputStream input) throws IOException {
		int size = input.readInt();
		if (size < 0 || size > MAX_CELL_BYTES) {
			throw new ExplorerSnapshotException("Invalid explorer snapshot cell size: " + size);
		}
		byte[] value = input.readNBytes(size);
		if (value.length != size) {
			throw new EOFException("Truncated explorer snapshot cell");
		}
		return value;
	}

	record DecodedChunk(List<ExplorerSnapshotColumn> columns, List<List<Object>> rows) {
	}
}
