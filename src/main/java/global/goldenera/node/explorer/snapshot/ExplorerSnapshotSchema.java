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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ExplorerSnapshotSchema {

	private ExplorerSnapshotSchema() {
	}

	static List<ExplorerSnapshotColumn> columns(Connection connection, ExplorerSnapshotTable table)
			throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table.tableName() + " WHERE 1 = 0")) {
			ResultSetMetaData metadata = resultSet.getMetaData();
			List<ExplorerSnapshotColumn> columns = new ArrayList<>(metadata.getColumnCount());
			for (int index = 1; index <= metadata.getColumnCount(); index++) {
				columns.add(new ExplorerSnapshotColumn(
						metadata.getColumnLabel(index).toLowerCase(Locale.ROOT),
						ExplorerSnapshotValueType.fromJdbcType(metadata.getColumnType(index),
								metadata.getColumnTypeName(index)),
						metadata.isNullable(index) != ResultSetMetaData.columnNoNulls));
			}
			return List.copyOf(columns);
		}
	}

	static String tableFingerprint(List<ExplorerSnapshotColumn> columns) {
		StringBuilder value = new StringBuilder();
		for (ExplorerSnapshotColumn column : columns) {
			value.append(column.name()).append(':').append(column.type()).append(':')
					.append(column.nullable()).append('\n');
		}
		return ExplorerSnapshotDigests.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
	}
}
