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
package global.goldenera.node.core.sync.snapshot.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;
import global.goldenera.node.shared.properties.GeneralProperties;

class SnapshotPublicationCoordinatorTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void publishDisabledIsACompleteNoOp() {
		Fixture fixture = new Fixture(50, hash(10), false, Instant.parse("2026-08-22T00:00:00Z"));
		fixture.properties.setPublishEnabled(false);

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.DISABLED);
		verify(fixture.coreProvider, never()).getIfAvailable();
	}

	@Test
	void headBelowMandatoryLagDoesNotGenerate() throws Exception {
		Fixture fixture = new Fixture(10, hash(13), false, Instant.parse("2026-08-22T00:00:00Z"));
		SnapshotPublicationCoordinator coordinator = new SnapshotPublicationCoordinator(
				fixture.properties, fixture.generalProperties, fixture.chainQuery,
				fixture.coreProvider, fixture.explorerProvider, fixture.store,
				ignored -> Optional.empty(), Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC));

		assertThat(coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.HEAD_BELOW_SAFETY_LAG);
		verify(fixture.coreProvider, never()).getIfAvailable();
	}

	@Test
	void missingAutomaticArtifactIsNormalRetryAndDoesNotAffectCoreHead() {
		Fixture fixture = new Fixture(60, hash(14), false, Instant.parse("2026-08-22T00:00:00Z"));
		when(fixture.coreProvider.getIfAvailable()).thenReturn(null);

		SnapshotPublicationCoordinator.AttemptResult result = fixture.coordinator.attempt();

		assertThat(result.outcome()).isEqualTo(SnapshotPublicationCoordinator.Outcome.RETRY_REQUIRED);
		assertThat(result.retryAfter()).isEqualTo(Duration.ofMinutes(1));
		assertThat(fixture.chainQuery.getLatestStoredBlockOrThrow()).isSameAs(fixture.head);
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory())).isEmpty();
	}

	@Test
	void startupPublishesArbitraryCurrentHeadAndSelectsItAtomically() throws Exception {
		Fixture fixture = new Fixture(137, hash(1), false, Instant.parse("2026-08-22T00:00:00Z"));

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);

		Path selected = new SnapshotPublicationDirectorySelector()
				.resolve(fixture.properties.getPublishDirectory()).orElseThrow();
		assertThat(selected.getFileName().toString())
				.isEqualTo(SnapshotFormatCompatibility.currentVersionName(137, hash(1)));
		assertThat(Files.readString(selected.resolve("manifest.json"))).isEqualTo("state-137");
	}

	@Test
	void duplicateAndHeadAdvanceAreIdempotentWithinDailyCycle() throws Exception {
		Fixture fixture = new Fixture(100, hash(2), false, Instant.parse("2026-08-22T00:00:00Z"));
		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		fixture.head(105, hash(3));

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.CADENCE_NOT_REACHED);
		assertThat(fixture.generated.get()).isEqualTo(1);
	}

	@Test
	void dailyCadenceRotatesCurrentAndRetainsPriorVersion() throws Exception {
		Fixture fixture = new Fixture(100, hash(4), false, Instant.parse("2026-08-22T00:00:00Z"));
		fixture.coordinator.attempt();
		fixture.head(105, hash(5));
		SnapshotPublicationCoordinator nextDay = fixture.coordinatorAt(
				Instant.parse("2026-08-23T00:00:01Z"));

		assertThat(nextDay.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		try (var versions = Files.list(fixture.properties.getPublishDirectory().resolve("versions"))) {
			assertThat(versions).hasSize(2);
		}
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory()).orElseThrow().getFileName().toString())
				.startsWith("snapshot-105-");

		fixture.head(110, hash(17));
		SnapshotPublicationCoordinator thirdDay = fixture.coordinatorAt(
				Instant.parse("2026-08-24T00:00:02Z"));
		assertThat(thirdDay.attempt().outcome()).isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		try (var versions = Files.list(fixture.properties.getPublishDirectory().resolve("versions"))) {
			assertThat(versions).hasSize(2);
		}
	}

	@Test
	void persistedPublicationTimeAllowsDurationCadenceAfterRestart() throws Exception {
		Instant first = Instant.parse("2026-08-22T00:00:00Z");
		Fixture fixture = new Fixture(200, hash(11), false, first);
		fixture.coordinator.attempt();
		fixture.head(200, hash(11));
		SnapshotPublicationCoordinator restarted = fixture.coordinatorAt(first.plus(Duration.ofHours(25)));

		assertThat(restarted.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		assertThat(fixture.generated.get()).isEqualTo(2);
	}

	@Test
	void explorerDisabledNeverResolvesExplorerProvider() {
		Fixture fixture = new Fixture(12, hash(6), false, Instant.parse("2026-08-22T00:00:00Z"));

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		verify(fixture.explorerProvider, never()).getIfAvailable();
	}

	@Test
	void laggingOrFailingExplorerStillPublishesCore() throws Exception {
		Fixture fixture = new Fixture(22, hash(7), true, Instant.parse("2026-08-22T00:00:00Z"));
		ExplorerSnapshotPublicationGenerator explorer = mock(ExplorerSnapshotPublicationGenerator.class);
		when(fixture.explorerProvider.getIfAvailable()).thenReturn(explorer);
		when(explorer.isExactlyCaughtUp(org.mockito.ArgumentMatchers.any())).thenReturn(true);
		org.mockito.Mockito.doThrow(new IllegalStateException("postgres lag"))
				.when(explorer).generate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory())).isPresent();
	}

	@Test
	void laggingExplorerIsNotAskedToGenerate() throws Exception {
		Fixture fixture = new Fixture(23, hash(12), true, Instant.parse("2026-08-22T00:00:00Z"));
		ExplorerSnapshotPublicationGenerator explorer = mock(ExplorerSnapshotPublicationGenerator.class);
		when(fixture.explorerProvider.getIfAvailable()).thenReturn(explorer);
		when(explorer.isExactlyCaughtUp(org.mockito.ArgumentMatchers.any())).thenReturn(false);

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		verify(explorer, never()).generate(
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void matureExplorerCapturePinsCoreGenerationToItsOlderSafeCanonicalAnchor() throws Exception {
		Fixture fixture = new Fixture(100, hash(15), true, Instant.parse("2026-08-23T00:00:01Z"));
		ExplorerSnapshotPublicationGenerator explorer = mock(ExplorerSnapshotPublicationGenerator.class);
		when(fixture.explorerProvider.getIfAvailable()).thenReturn(explorer);
		SnapshotPublicationAnchor preferred = new SnapshotPublicationAnchor(80, hash(16), 20);
		when(explorer.preferredCoreAnchor(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(preferred));
		when(explorer.isExactlyCaughtUp(org.mockito.ArgumentMatchers.any())).thenReturn(true);
		StoredBlock preferredBlock = mock(StoredBlock.class);
		when(preferredBlock.getHeight()).thenReturn(preferred.height());
		when(preferredBlock.getHash()).thenReturn(preferred.hash());
		when(fixture.chainQuery.getStoredBlockByHeight(preferred.height())).thenReturn(Optional.of(preferredBlock));

		assertThat(fixture.coordinator.attempt().outcome())
				.isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory()).orElseThrow().getFileName().toString())
				.startsWith("snapshot-80-");
	}

	@Test
	void failedNewGenerationRetainsPriorAndPersistsBoundedBackoff() {
		Fixture fixture = new Fixture(30, hash(8), false, Instant.parse("2026-08-22T00:00:00Z"));
		fixture.coordinator.attempt();
		Path prior = new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory()).orElseThrow();
		fixture.head(31, hash(9));
		SnapshotPublicationCoordinator nextDay = fixture.coordinatorAt(
				Instant.parse("2026-08-23T00:00:01Z"));
		when(fixture.coreProvider.getIfAvailable()).thenReturn((height, hash, output) -> {
			throw new IllegalStateException("generation failed");
		});

		SnapshotPublicationCoordinator.AttemptResult failed = nextDay.attempt();

		assertThat(failed.outcome()).isEqualTo(SnapshotPublicationCoordinator.Outcome.RETRY_REQUIRED);
		assertThat(failed.retryAfter()).isEqualTo(Duration.ofMinutes(1));
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory()).orElseThrow()).isEqualTo(prior);
		assertThat(fixture.store.loadState().failures()).isEqualTo(1);
	}

	@Test
	void reorgWithdrawsCurrentBeforeCadenceAndPublishesCanonicalReplacement() throws Exception {
		Fixture fixture = new Fixture(100, hash(18), false, Instant.parse("2026-08-22T00:00:00Z"));
		assertThat(fixture.coordinator.attempt().outcome()).isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		when(fixture.chainQuery.getStoredBlockByHeight(100)).thenReturn(Optional.empty());
		fixture.head(101, hash(19));

		assertThat(fixture.coordinator.attempt().outcome()).isEqualTo(SnapshotPublicationCoordinator.Outcome.PUBLISHED);
		assertThat(fixture.generated).hasValue(2);
		assertThat(new SnapshotPublicationDirectorySelector().resolve(
				fixture.properties.getPublishDirectory()).orElseThrow().getFileName().toString())
				.startsWith("snapshot-101-");
	}

	private final class Fixture {
		private final SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		private final GeneralProperties generalProperties = new GeneralProperties();
		private final ChainQuery chainQuery = mock(ChainQuery.class);
		private final ObjectProvider<CoreSnapshotPublicationGenerator> coreProvider = provider();
		private final ObjectProvider<ExplorerSnapshotPublicationGenerator> explorerProvider = provider();
		private final SnapshotPublicationStore store;
		private final AtomicInteger generated = new AtomicInteger();
		private final SnapshotPublicationCoordinator coordinator;
		private StoredBlock head;

		private Fixture(long height, Hash hash, boolean explorer, Instant now) {
			try {
				Path root = Files.createDirectory(temporaryDirectory.resolve("publish-" + height));
				properties.setPublishEnabled(true);
				properties.setPublishDirectory(root);
				properties.setPublishCycle(Duration.ofHours(24));
				properties.setPublishMinimumLagBlocks(0);
				properties.setPublishRetryInitialBackoff(Duration.ofMinutes(1));
				properties.setPublishRetryMaxBackoff(Duration.ofHours(1));
				generalProperties.setExplorerEnable(explorer);
				store = new SnapshotPublicationStore(root);
				head(height, hash);
				when(coreProvider.getIfAvailable()).thenReturn((snapshotHeight, snapshotHash, output) -> {
					generated.incrementAndGet();
					Files.createDirectory(output);
					Files.writeString(output.resolve("manifest.json"), "state-" + snapshotHeight);
					Files.writeString(output.resolve("archive-manifest.json"), "archive-" + snapshotHeight);
					return new VerifiedCorePublication(snapshotHeight, snapshotHash, output);
				});
				coordinator = new SnapshotPublicationCoordinator(
						properties, generalProperties, chainQuery, coreProvider, explorerProvider, store,
						ignored -> Optional.of(new SnapshotPublicationAnchor(
								head.getHeight(), head.getHash(), 1)), Clock.fixed(now, ZoneOffset.UTC));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		private void head(long height, Hash hash) {
			head = mock(StoredBlock.class);
			when(head.getHeight()).thenReturn(height);
			when(head.getHash()).thenReturn(hash);
			when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(ignored -> head);
			when(chainQuery.getStoredBlockByHeight(height)).thenReturn(Optional.of(head));
		}

		private SnapshotPublicationCoordinator coordinatorAt(Instant now) {
			return new SnapshotPublicationCoordinator(
					properties, generalProperties, chainQuery, coreProvider, explorerProvider, store,
					ignored -> Optional.of(new SnapshotPublicationAnchor(
							head.getHeight(), head.getHash(), 1)), Clock.fixed(now, ZoneOffset.UTC));
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

	private static Hash hash(int value) {
		return Hash.fromHexString("0x" + "%02x".formatted(value).repeat(32));
	}
}
