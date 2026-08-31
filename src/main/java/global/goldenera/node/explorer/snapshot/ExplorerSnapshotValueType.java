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

import java.sql.Types;

public enum ExplorerSnapshotValueType {
	BOOLEAN,
	INT32,
	INT64,
	DECIMAL,
	STRING,
	BYTES,
	TIMESTAMP,
	JSON;

	static ExplorerSnapshotValueType fromJdbcType(int jdbcType, String typeName) {
		return switch (jdbcType) {
			case Types.BOOLEAN, Types.BIT -> BOOLEAN;
			case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> INT32;
			case Types.BIGINT -> INT64;
			case Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> DECIMAL;
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
					Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> STRING;
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> BYTES;
			case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.DATE -> TIMESTAMP;
			case Types.OTHER -> typeName != null && typeName.toLowerCase().contains("json") ? JSON : STRING;
			default -> throw new ExplorerSnapshotException(
					"Unsupported explorer snapshot JDBC type " + jdbcType + " (" + typeName + ")");
		};
	}
}
