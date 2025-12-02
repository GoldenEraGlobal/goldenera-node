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
package global.goldenera.node.core.p2p.reputation;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.storage.peers.PeerReputationRepository;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class PeerReputationService {

	PeerReputationRepository repository;

	ConcurrentHashMap<Address, PeerReputationRecord> cache = new ConcurrentHashMap<>();

	public PeerReputationRecord recordFailure(Address identity) {
		return update(identity, record -> record.withFailure(Instant.now()));
	}

	public PeerReputationRecord recordSuccess(Address identity) {
		return update(identity, record -> record.withSuccess(Instant.now()));
	}

	public PeerReputationRecord ban(Address identity) {
		return update(identity, record -> record.banned(Instant.now()));
	}

	public boolean isBanned(Address identity) {
		return identity != null && get(identity).isBanned();
	}

	public int score(Address identity) {
		return identity == null ? Integer.MIN_VALUE : get(identity).reliabilityScore();
	}

	private PeerReputationRecord get(Address identity) {
		if (identity == null) {
			return PeerReputationRecord.initial();
		}
		return cache.compute(identity, (key, current) -> {
			PeerReputationRecord record = current;
			if (record == null) {
				record = repository.find(key).orElse(PeerReputationRecord.initial());
			}
			PeerReputationRecord checked = record.checkExpiration(Instant.now());
			if (checked != record) {
				repository.save(key, checked);
				return checked;
			}
			return record;
		});
	}

	private PeerReputationRecord update(Address identity,
			Function<PeerReputationRecord, PeerReputationRecord> updater) {
		if (identity == null) {
			log.debug("Skipping reputation update for null identity");
			return PeerReputationRecord.initial();
		}
		return cache.compute(identity, (key, current) -> {
			PeerReputationRecord base = current != null ? current
					: repository.find(key)
							.orElse(PeerReputationRecord.initial());
			base = base.checkExpiration(Instant.now());

			PeerReputationRecord updated = updater.apply(base);
			repository.save(key, updated);
			return updated;
		});
	}
}
