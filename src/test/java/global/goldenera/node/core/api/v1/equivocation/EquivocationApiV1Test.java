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
package global.goldenera.node.core.api.v1.equivocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.storage.blockchain.EquivocationEvidenceRepository;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence.SignedHeader;
import global.goldenera.node.shared.api.v1.equivocation.EquivocationEvidenceDtoV1;
import global.goldenera.node.shared.api.v1.equivocation.EquivocationEvidenceMapper;

class EquivocationApiV1Test {

	@Test
	void mapsPersistentEvidenceToApiDto() {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString("0x01"));
		Address identity = key.getAddress();
		BlockHeader first = signedHeader(9, 1, key);
		BlockHeader second = signedHeader(9, 2, key);
		EquivocationEvidence evidence = new EquivocationEvidence(9, identity,
				List.of(SignedHeader.from(first), SignedHeader.from(second)).stream()
						.sorted((left, right) -> left.blockHash().compareTo(right.blockHash())).toList(),
				Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
		when(repository.findConflicts(10)).thenReturn(List.of(evidence));

		EquivocationApiV1 api = new EquivocationApiV1(repository, new EquivocationEvidenceMapper());
		List<EquivocationEvidenceDtoV1> body = api.list(10).getBody();

		assertThat(body).hasSize(1);
		assertThat(body.getFirst().height()).isEqualTo(9);
		assertThat(body.getFirst().identity()).isEqualTo(identity);
		assertThat(body.getFirst().signedHeaders()).extracting(header -> header.blockHash())
				.containsExactlyInAnyOrder(first.getHash(), second.getHash());
		assertThat(body.getFirst().signedHeaders()).allSatisfy(header ->
				assertThat(header.canonicalSignedHeader().isEmpty()).isFalse());
	}

	private BlockHeader signedHeader(long height, long nonce, PrivateKey key) {
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(Instant.EPOCH.plusSeconds(height))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(key.getAddress())
				.nonce(nonce)
				.build();
		return unsigned.toBuilder().signature(key.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
	}
}
