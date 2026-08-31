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
package global.goldenera.node.core.sync.snapshot.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.NodeLoader;
import global.goldenera.merkletrie.patricia.SimpleMerklePatriciaTrie;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;

class CoreSnapshotEntityIndexVerifierTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void decodesFrozenCurrentEntityChunkAndRejectsOtherVersionsBeforeEntries() throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(output)) {
			CoreSnapshotEntityChunkCodec.writeHeader(data, 0, CoreSnapshotEntityType.TOKEN, 0);
		}
		byte[] golden = output.toByteArray();
		assertThat(Hash.hash(Bytes.wrap(golden)).toHexString())
				.isEqualTo("0xf8c84633af08890d302f1db35bfb30a56b022dfdd84106089057d021fbe80ac9");
		CoreSnapshotEntityChunkDescriptor descriptor = new CoreSnapshotEntityChunkDescriptor(
				0, CoreSnapshotEntityType.TOKEN, 0, golden.length, Hash.hash(Bytes.wrap(golden)),
				golden.length, Hash.hash(Bytes.wrap(golden)));
		try (CoreSnapshotEntityChunkCodec.Reader reader = CoreSnapshotEntityChunkCodec.open(
				new ByteArrayInputStream(golden), descriptor)) {
			reader.finish();
		}

		for (int unsupported : new int[] { 0, 2 }) {
			byte[] wrong = golden.clone();
			wrong[7] = (byte) unsupported;
			assertThatThrownBy(() -> CoreSnapshotEntityChunkCodec.open(
					new ByteArrayInputStream(wrong), descriptor))
					.isInstanceOf(SnapshotVerificationException.class)
					.hasMessageContaining("header does not match");
		}
	}

	@Test
	void verifiesCanonicalEntitySidecarAndTrieCompleteness() throws Exception {
		Fixture fixture = fixture();
		Path output = Files.createDirectory(temporaryDirectory.resolve("entities"));
		CoreSnapshotEntityIndexExporter.ExportResult exported =
				new CoreSnapshotEntityIndexExporter().export(fixture.source(), output);

		CoreSnapshotEntityIndexVerifier.VerificationResult result =
				new CoreSnapshotEntityIndexVerifier().verify(
						fixture.root(), fixture.nodeLoader(), exported.descriptors(),
						descriptor -> Files.newInputStream(exported.chunkFiles().get(descriptor.index())));

		assertThat(result.entryCounts()).containsEntry(CoreSnapshotEntityType.TOKEN, 1L);
		assertThat(result.entryCounts()).containsEntry(CoreSnapshotEntityType.AUTHORITY, 0L);
		assertThat(result.entryCounts()).containsEntry(CoreSnapshotEntityType.VALIDATOR, 0L);
		assertThat(result.totalEntries()).isOne();
		assertThat(exported.descriptors()).hasSize(3);
	}

	@Test
	void rejectsOmittedNonEmptyMaterializedIndex() {
		Fixture fixture = fixture();

		assertThatThrownBy(() -> new CoreSnapshotEntityIndexVerifier().verify(
				fixture.root(), fixture.nodeLoader(), List.of(),
				descriptor -> new ByteArrayInputStream(new byte[0])))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("incomplete for TOKEN");
	}

	private Fixture fixture() {
		TokenState token = TokenStateImpl.builder()
				.version(TokenStateVersion.V1)
				.name("GoldenEra")
				.smallestUnitName("GE")
				.numberOfDecimals(18)
				.userBurnable(true)
				.totalSupply(Wei.valueOf(1))
				.originTxHash(Hash.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(Instant.EPOCH)
				.build();
		SimpleMerklePatriciaTrie<Bytes, Bytes> tokenTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		tokenTrie.put(Address.NATIVE_TOKEN, CoreSnapshotEntityStateCodec.encodeToken(token));
		Hash tokenRoot = Hash.wrap(tokenTrie.getRootHash());

		SimpleMerklePatriciaTrie<Bytes, Bytes> mainTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		mainTrie.put(WorldStateFactory.KEY_TOKEN, tokenRoot);
		Hash mainRoot = Hash.wrap(mainTrie.getRootHash());

		Map<Hash, Bytes> nodes = new LinkedHashMap<>();
		collect(mainTrie, mainRoot, nodes);
		collect(tokenTrie, tokenRoot, nodes);
		NodeLoader loader = (location, hash) -> Optional.ofNullable(nodes.get(Hash.wrap(hash)));
		CoreSnapshotEntityIndexSource source = new CoreSnapshotEntityIndexSource() {
			@Override
			public Map<Address, TokenState> tokens() {
				return Map.of(Address.NATIVE_TOKEN, token);
			}

			@Override
			public Map<Address, AuthorityState> authorities() {
				return Map.of();
			}

			@Override
			public Map<Address, ValidatorState> validators() {
				return Map.of();
			}
		};
		return new Fixture(mainRoot, loader, source);
	}

	private void collect(MerkleTrie<Bytes, Bytes> trie, Hash root, Map<Hash, Bytes> nodes) {
		trie.visitAll(node -> {
			if (node.getHash().equals(root) || node.isReferencedByHash()) {
				nodes.put(Hash.wrap(node.getHash()), node.getEncodedBytes());
			}
		});
	}

	private record Fixture(Hash root, NodeLoader nodeLoader, CoreSnapshotEntityIndexSource source) {
	}
}
