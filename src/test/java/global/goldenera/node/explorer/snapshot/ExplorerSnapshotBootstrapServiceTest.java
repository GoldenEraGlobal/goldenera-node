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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;

class ExplorerSnapshotBootstrapServiceTest {

	private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
			1, 1, "sandbox", "0x" + "1".repeat(64), "2".repeat(64));

	@Test
	void noFloorPreservesLegacyIndexerPathWithoutTouchingExplorerStorageOrHttp() throws Exception {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		DataSource dataSource = mock(DataSource.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		ExplorerSnapshotRemoteSource remote = mock(ExplorerSnapshotRemoteSource.class);
		ExplorerCheckpointSnapshotImporter importer = mock(ExplorerCheckpointSnapshotImporter.class);
		ExplorerSnapshotBootstrapService service = new ExplorerSnapshotBootstrapService(
				properties, CoreSnapshotCheckpointFloorPolicy.withoutFloor(), chainQuery,
				dataSource, remote, importer);

		assertThat(service.prepareForIndexing(IDENTITY))
				.isEqualTo(ExplorerSnapshotBootstrapService.Outcome.LEGACY_NO_FLOOR);
		verify(dataSource, never()).getConnection();
		verify(remote, never()).stageFromFirstTrustedSource(any());
		verify(importer, never()).importIntoEmptySchema(any(), any());
		verifyNoInteractions(chainQuery);
	}

	@Test
	void activeFloorAndUnavailableExplorerSnapshotFailClosedWithoutCoreMutation() throws Exception {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setBootstrapEnabled(true);
		DataSource dataSource = emptyExplorerDataSource();
		ExplorerSnapshotRemoteSource remote = mock(ExplorerSnapshotRemoteSource.class);
		when(remote.stageFromFirstTrustedSource(any()))
				.thenThrow(new ExplorerSnapshotException("unavailable"));
		ExplorerCheckpointSnapshotImporter importer = mock(ExplorerCheckpointSnapshotImporter.class);
		CoreSnapshotCheckpointFloor floor = floor();
		ExplorerSnapshotBootstrapService service = new ExplorerSnapshotBootstrapService(
				properties, CoreSnapshotCheckpointFloorPolicy.enforcing(floor), mock(ChainQuery.class),
				dataSource, remote, importer);

		assertThatThrownBy(() -> service.prepareForIndexing(IDENTITY))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("unavailable");
		verify(remote).stageFromFirstTrustedSource(new ExplorerSnapshotBinding(
				1, "sandbox", IDENTITY.genesisHash(), 42, floor.blockHash().toHexString(),
				floor.stateRoot().toHexString(), "4".repeat(64), "5".repeat(64)));
		verify(importer, never()).importIntoEmptySchema(any(), any());
	}

	@Test
	void disabledSnapshotBootstrapWithActiveFloorNeverCallsRemoteOrImporter() throws Exception {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setBootstrapEnabled(false);
		ExplorerSnapshotRemoteSource remote = mock(ExplorerSnapshotRemoteSource.class);
		ExplorerCheckpointSnapshotImporter importer = mock(ExplorerCheckpointSnapshotImporter.class);
		ExplorerSnapshotBootstrapService service = new ExplorerSnapshotBootstrapService(
				properties, CoreSnapshotCheckpointFloorPolicy.enforcing(floor()), mock(ChainQuery.class),
				emptyExplorerDataSource(), remote, importer);

		assertThatThrownBy(() -> service.prepareForIndexing(IDENTITY))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("disabled while core starts above genesis");
		verify(remote, never()).stageFromFirstTrustedSource(any());
		verify(importer, never()).importIntoEmptySchema(any(), any());
	}

	private static CoreSnapshotCheckpointFloor floor() {
		return new CoreSnapshotCheckpointFloor(
				42, Hash.fromHexString("0x" + "2".repeat(64)), Hash.fromHexString("0x" + "3".repeat(64)),
				BigInteger.valueOf(2_000), Hash.fromHexString("0x" + "4".repeat(64)),
				Hash.fromHexString("0x" + "5".repeat(64)));
	}

	private static DataSource emptyExplorerDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:explorer-bootstrap-" + UUID.randomUUID()
						+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			jdbc.execute("CREATE TABLE " + table.tableName() + " (id INT)");
		}
		return dataSource;
	}
}
