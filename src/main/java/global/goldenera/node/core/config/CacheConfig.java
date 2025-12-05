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
package global.goldenera.node.core.config;

import static lombok.AccessLevel.PRIVATE;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.consensus.state.AuthorityState;
import global.goldenera.node.shared.consensus.state.TokenState;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Configuration
@EnableCaching
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class CacheConfig {
	@Bean("trieNodeCache")
	public Cache<Hash, Bytes> trieNodeCache() {
		return Caffeine.newBuilder()
				.maximumWeight(256 * 1024 * 1024) // 256MB
				.weigher((Hash key, Bytes value) -> value.size())
				.expireAfterAccess(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("blockCache")
	public Cache<Hash, StoredBlock> blockCache() {
		return Caffeine.newBuilder()
				.maximumWeight(256 * 1024 * 1024) // 256MB
				.weigher((Hash key, StoredBlock value) -> value.getSize())
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("headerCache")
	public Cache<Hash, StoredBlock> headerCache() {
		return Caffeine.newBuilder()
				.maximumSize(50_000) // Partial blocks are smaller (no tx body)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("heightCache")
	public Cache<Long, Hash> heightCache() {
		return Caffeine.newBuilder()
				.maximumSize(100_000)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("txCache")
	public Cache<Hash, Tx> txCache() {
		return Caffeine.newBuilder()
				.maximumWeight(128 * 1024 * 1024) // 128MB
				.weigher((Hash key, Tx value) -> value.getSize())
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("tokensCache")
	public Cache<String, List<TokenState>> tokensCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("authoritiesCache")
	public Cache<String, List<AuthorityState>> authoritiesCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("tokensMapCache")
	public Cache<String, Map<Address, TokenState>> tokensMapCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	@Bean("authoritiesMapCache")
	public Cache<String, Map<Address, AuthorityState>> authoritiesMapCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}
}
