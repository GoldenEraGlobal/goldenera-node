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
package global.goldenera.node.core.sync.snapshot.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService.PublicationResult;
import global.goldenera.node.shared.properties.GeneralProperties;

class OfflineSnapshotOperatorServiceTest {

	private static final long HEIGHT = 777_777;

	@TempDir
	Path temporaryDirectory;

	OfflineSnapshotOperatorProperties properties;
	SnapshotDistributionProperties distribution;
	GeneralProperties general;
	NetworkSettingsProvider settings;
	AuthoritativeChainIdentityProvider identityProvider;
	LiveHeadCoreSnapshotCloneService cloneService;
	IsolatedLiveHeadSnapshotPublisher publisher;

	@BeforeEach
	void setUp() {
		properties = new OfflineSnapshotOperatorProperties();
		properties.setEnabled(true);
		properties.setCheckpointHeight(HEIGHT);
		properties.setOutputDirectory(temporaryDirectory.resolve("publication"));
		properties.setPublicOrigin(URI.create("https://node-eu2.goldenera.global/"));
		distribution = new SnapshotDistributionProperties();
		general = new GeneralProperties();
		settings = mock(NetworkSettingsProvider.class);
		when(settings.currentProfile()).thenReturn("prod");
		identityProvider = mock(AuthoritativeChainIdentityProvider.class);
		when(identityProvider.identity()).thenReturn(mainnetIdentity());
		cloneService = mock(LiveHeadCoreSnapshotCloneService.class);
		publisher = mock(IsolatedLiveHeadSnapshotPublisher.class);
	}

	@Test
	void publishesCoreOnlyWithoutExplorerOrPostgresqlDependency() throws Exception {
		LiveHeadCoreSnapshotClone clone = fixtureClone();
		when(cloneService.create(HEIGHT, null)).thenReturn(clone);
		PublicationResult result = mock(PublicationResult.class);
		when(result.publicationDirectory()).thenReturn(properties.getOutputDirectory());
		when(publisher.publish(clone, properties)).thenReturn(result);
		when(cloneService.isStillCanonical(clone.height(), clone.hash())).thenReturn(true);

		service().publish();

		verify(publisher).publish(clone, properties);
	}

	@Test
	void removesPublishedArtifactAndFailsWhenLiveAnchorReorgsDuringExport() throws Exception {
		LiveHeadCoreSnapshotClone clone = fixtureClone();
		when(cloneService.create(HEIGHT, null)).thenReturn(clone);
		PublicationResult result = mock(PublicationResult.class);
		when(result.publicationDirectory()).thenReturn(properties.getOutputDirectory());
		when(publisher.publish(clone, properties)).thenAnswer(ignored -> {
			Files.createDirectory(properties.getOutputDirectory());
			Files.writeString(properties.getOutputDirectory().resolve("manifest.json"), "orphaned");
			return result;
		});
		when(cloneService.isStillCanonical(clone.height(), clone.hash())).thenReturn(false);

		assertThatThrownBy(() -> service().publish())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("canonical anchor changed");
		assertThat(properties.getOutputDirectory()).doesNotExist();
	}

	@Test
	void optionalExplorerRequiresExplorerRuntime() throws Exception {
		properties.setIncludeExplorer(true);

		assertThatThrownBy(() -> service().publish())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("enabled PostgreSQL explorer");
		verify(cloneService, never()).create(HEIGHT, null);
	}

	@Test
	void rejectsUnknownProductionIdentityBeforeCloneCreation() throws Exception {
		when(settings.currentProfile()).thenReturn("dev");

		assertThatThrownBy(() -> service().publish())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("known production identity");
		verify(cloneService, never()).create(HEIGHT, null);
	}

	private OfflineSnapshotOperatorService service() {
		return new OfflineSnapshotOperatorService(
				properties, distribution, general, settings, identityProvider, cloneService, publisher);
	}

	private LiveHeadCoreSnapshotClone fixtureClone() throws Exception {
		Path directory = Files.createDirectory(temporaryDirectory.resolve("clone"));
		return new LiveHeadCoreSnapshotClone(
				directory, HEIGHT, Hash.ZERO, MerkleTrie.EMPTY_TRIE_NODE_HASH, BigInteger.ONE, mainnetIdentity());
	}

	private StoredChainIdentity mainnetIdentity() {
		return new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				0,
				"mainnet",
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f",
				null);
	}
}
