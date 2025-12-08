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
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Application-level cache configuration using Caffeine.
 * All cache sizes are externalized to BlockchainDbProperties.
 */
@Configuration
@EnableCaching
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class CacheConfig {

	BlockchainDbProperties props;

	/**
	 * Cache for WorldState MPT trie nodes.
	 * Critical for state access performance - point lookups by hash.
	 */
	@Bean("trieNodeCache")
	public Cache<Hash, Bytes> trieNodeCache() {
		long maxBytes = props.getCacheTrieNodeMb() * 1024L * 1024L;
		log.info("Initializing trieNodeCache: {}MB, expire: {}min", props.getCacheTrieNodeMb(),
				props.getCacheExpireMinutes());
		return Caffeine.newBuilder()
				.maximumWeight(maxBytes)
				.weigher((Hash key, Bytes value) -> value.size())
				.expireAfterAccess(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for full StoredBlock objects (with transactions).
	 * Used for recently accessed blocks.
	 */
	@Bean("blockCache")
	public Cache<Hash, StoredBlock> blockCache() {
		long maxBytes = props.getCacheBlockMb() * 1024L * 1024L;
		log.info("Initializing blockCache: {}MB, expire: {}min", props.getCacheBlockMb(),
				props.getCacheExpireMinutes());
		return Caffeine.newBuilder()
				.maximumWeight(maxBytes)
				.weigher((Hash key, StoredBlock value) -> value.getEncodedSize())
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for partial StoredBlocks (headers only, no tx body).
	 * More memory-efficient for header-only operations.
	 */
	@Bean("headerCache")
	public Cache<Hash, StoredBlock> headerCache() {
		log.info("Initializing headerCache: {} entries, expire: {}min", props.getCacheHeaderMaxEntries(),
				props.getCacheExpireMinutes());
		return Caffeine.newBuilder()
				.maximumSize(props.getCacheHeaderMaxEntries())
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for height-to-hash mapping.
	 * Fast lookup of block hash by height.
	 */
	@Bean("heightCache")
	public Cache<Long, Hash> heightCache() {
		log.info("Initializing heightCache: {} entries, expire: {}min", props.getCacheHeightMaxEntries(),
				props.getCacheExpireMinutes());
		return Caffeine.newBuilder()
				.maximumSize(props.getCacheHeightMaxEntries())
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for recently accessed transactions.
	 */
	@Bean("txCache")
	public Cache<Hash, Tx> txCache() {
		long maxBytes = props.getCacheTxMb() * 1024L * 1024L;
		log.info("Initializing txCache: {}MB, expire: {}min", props.getCacheTxMb(), props.getCacheExpireMinutes());
		return Caffeine.newBuilder()
				.maximumWeight(maxBytes)
				.weigher((Hash key, Tx value) -> value.getSize())
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for token list (global, rarely changes).
	 */
	@Bean("tokensCache")
	public Cache<String, List<TokenState>> tokensCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for authorities list (global, rarely changes).
	 */
	@Bean("authoritiesCache")
	public Cache<String, List<AuthorityState>> authoritiesCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for token map (address -> TokenState).
	 */
	@Bean("tokensMapCache")
	public Cache<String, Map<Address, TokenState>> tokensMapCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}

	/**
	 * Cache for authorities map (address -> AuthorityState).
	 */
	@Bean("authoritiesMapCache")
	public Cache<String, Map<Address, AuthorityState>> authoritiesMapCache() {
		return Caffeine.newBuilder()
				.maximumSize(1)
				.expireAfterWrite(props.getCacheExpireMinutes(), TimeUnit.MINUTES)
				.build();
	}
}
