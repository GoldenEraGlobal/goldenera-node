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
package global.goldenera.node.explorer.storage.chainidentity;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import global.goldenera.node.core.storage.chainidentity.ChainIdentityStore;
import global.goldenera.node.core.storage.chainidentity.ChainStorageGuardException;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/** Downstream explorer mirror of the authoritative RocksDB chain identity. */
public class PostgresChainIdentityRepository implements ChainIdentityStore {

	private static final String SELECT_IDENTITY = """
			SELECT id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint
			FROM explorer_chain_identity
			ORDER BY id
			""";
	private static final String INSERT_IDENTITY = """
			INSERT INTO explorer_chain_identity
				(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
			VALUES (1, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO NOTHING
			""";

	private static final RowMapper<StoredChainIdentity> IDENTITY_ROW_MAPPER = (resultSet, rowNumber) -> {
		if (resultSet.getInt("id") != 1) {
			throw new ChainStorageGuardException("PostgreSQL chain identity row has an invalid singleton ID");
		}
		return new StoredChainIdentity(
					resultSet.getInt("format_version"),
					resultSet.getInt("carrier_network_code"),
					resultSet.getString("chain_id"),
					resultSet.getString("genesis_hash"),
					resultSet.getString("manifest_fingerprint"));
	};

	private final JdbcTemplate jdbcTemplate;

	@Override
	public String name() {
		return "PostgreSQL";
	}

	@Override
	public Optional<StoredChainIdentity> find() {
		List<StoredChainIdentity> identities = jdbcTemplate.query(SELECT_IDENTITY, IDENTITY_ROW_MAPPER);
		if (identities.size() > 1) {
			throw new ChainStorageGuardException("PostgreSQL chain identity table contains multiple rows");
		}
		return identities.stream().findFirst();
	}

	@Override
	public void bindIfAbsent(StoredChainIdentity identity) {
		jdbcTemplate.update(
				INSERT_IDENTITY,
				identity.formatVersion(),
				identity.carrierNetworkCode(),
				identity.chainId(),
				identity.genesisHash(),
				identity.manifestFingerprint());
	}
}
