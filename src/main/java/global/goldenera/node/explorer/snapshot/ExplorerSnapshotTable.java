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

import java.util.List;

/** Whitelist of chain-derived explorer data that is safe to copy between nodes. */
public enum ExplorerSnapshotTable {
	BLOCK_HEADER("explorer_block_header", List.of("height", "hash")),
	TX("explorer_tx", List.of("block_height", "tx_index", "hash")),
	TRANSFER("explorer_transfer", List.of("block_height", "id")),
	ACCOUNT_BALANCE("explorer_account_balance", List.of("address", "token_address")),
	ACCOUNT_NONCE("explorer_account_nonce", List.of("address")),
	ADDRESS_ALIAS("explorer_address_alias", List.of("alias")),
	AUTHORITY("explorer_authority", List.of("address")),
	BIP_STATE("explorer_bip_state", List.of("bip_hash")),
	NETWORK_PARAMS("explorer_network_params", List.of("id")),
	TOKEN("explorer_token", List.of("address")),
	VALIDATOR("explorer_validator", List.of("address")),
	REVERT_LOG("explorer_revert_log", List.of("block_height", "id")),
	STATUS("explorer_status", List.of("id"));

	public static final int SCHEMA_VERSION = 1;

	private final String tableName;
	private final List<String> orderColumns;

	ExplorerSnapshotTable(String tableName, List<String> orderColumns) {
		this.tableName = tableName;
		this.orderColumns = orderColumns;
	}

	public String tableName() {
		return tableName;
	}

	public List<String> orderColumns() {
		return orderColumns;
	}
}
