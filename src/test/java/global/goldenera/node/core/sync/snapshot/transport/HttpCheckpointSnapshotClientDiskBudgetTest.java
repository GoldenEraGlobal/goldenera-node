/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.transport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpCheckpointSnapshotClientDiskBudgetTest {

	@Test
	void requiresPayloadAndBoundedFreeSpaceReserve() {
		long oneMiB = 1024L * 1024;

		assertThat(HttpCheckpointSnapshotClient.hasSufficientUsableSpace(oneMiB, 2 * oneMiB)).isTrue();
		assertThat(HttpCheckpointSnapshotClient.hasSufficientUsableSpace(oneMiB, 2 * oneMiB - 1)).isFalse();
		assertThat(HttpCheckpointSnapshotClient.hasSufficientUsableSpace(Long.MAX_VALUE, Long.MAX_VALUE)).isFalse();
	}
}
