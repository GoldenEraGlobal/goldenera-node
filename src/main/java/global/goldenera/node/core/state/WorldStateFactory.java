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
package global.goldenera.node.core.state;

import static lombok.AccessLevel.PRIVATE;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.NodeLoader;
import global.goldenera.merkletrie.patricia.StoredMerklePatriciaTrie;
import global.goldenera.merkletrie.patricia.StoredNodeFactory;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorage;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorageFactory;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WorldStateFactory {

	RocksDBMerkleStorageFactory rocksDBMerkleStorageFactory;

	// Keys for the Main Trie
	public static final Bytes KEY_BALANCE = Bytes.wrap("balance".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_NONCE = Bytes.wrap("nonce".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_AUTHORITY = Bytes.wrap("authority".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_ADDRESS_ALIAS = Bytes.wrap("address_alias".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_BIP_STATE = Bytes.wrap("bipstate".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_NETWORK_PARAMS = Bytes.wrap("network_params".getBytes(StandardCharsets.UTF_8));
	public static final Bytes KEY_TOKEN = Bytes.wrap("token".getBytes(StandardCharsets.UTF_8));

	Function<Bytes, Bytes> rootStateSerializer;
	Function<Bytes, Bytes> rootStateDeserializer;

	Function<AccountBalanceState, Bytes> balanceSerializer;
	Function<Bytes, AccountBalanceState> balanceDeserializer;

	Function<AccountNonceState, Bytes> nonceSerializer;
	Function<Bytes, AccountNonceState> nonceDeserializer;

	Function<AddressAliasState, Bytes> addressAliasSerializer;
	Function<Bytes, AddressAliasState> addressAliasDeserializer;

	Function<AuthorityState, Bytes> authoritySerializer;
	Function<Bytes, AuthorityState> authorityDeserializer;

	Function<BipState, Bytes> bipStateSerializer;
	Function<Bytes, BipState> bipStateDeserializer;

	Function<NetworkParamsState, Bytes> networkParamsSerializer;
	Function<Bytes, NetworkParamsState> networkParamsDeserializer;

	Function<TokenState, Bytes> tokenSerializer;
	Function<Bytes, TokenState> tokenDeserializer;

	/**
	 * Creates a new WorldState for VALIDATION (Explorer Friendly).
	 * Journal: OFF, Initial State Capture: ON
	 */
	public WorldState createForValidation(Hash parentStateRoot) {
		return create(parentStateRoot, false);
	}

	/**
	 * Creates a new WorldState for MINING (Performance Optimized).
	 * Journal: ON, Initial State Capture: OFF
	 */
	public WorldState createForMining(Hash parentStateRoot) {
		return create(parentStateRoot, true);
	}

	/**
	 * Generic create method.
	 * 
	 * @param isMining
	 *            if true, enables rollback journal but disables initial state
	 *            capture.
	 */
	public WorldState create(Hash parentStateRoot, boolean isMining) {
		RocksDBMerkleStorage rocksDBMerkleStorage = rocksDBMerkleStorageFactory.create();
		NodeLoader nodeLoader = rocksDBMerkleStorage::get;

		StoredNodeFactory<Bytes> mainNodeFactory = new StoredNodeFactory<>(
				nodeLoader, rootStateSerializer, rootStateDeserializer);
		MerkleTrie<Bytes, Bytes> mainTrie = new StoredMerklePatriciaTrie<>(
				mainNodeFactory, parentStateRoot);

		// Load Sub-Tries
		MerkleTrie<Bytes, AccountBalanceState> balanceTrie = loadSubTrie(
				mainTrie, KEY_BALANCE, nodeLoader, balanceSerializer, balanceDeserializer);

		MerkleTrie<Bytes, AccountNonceState> nonceTrie = loadSubTrie(
				mainTrie, KEY_NONCE, nodeLoader, nonceSerializer, nonceDeserializer);

		MerkleTrie<Bytes, AuthorityState> authorityTrie = loadSubTrie(
				mainTrie, KEY_AUTHORITY, nodeLoader, authoritySerializer, authorityDeserializer);

		MerkleTrie<Bytes, AddressAliasState> addressAliasTrie = loadSubTrie(
				mainTrie, KEY_ADDRESS_ALIAS, nodeLoader, addressAliasSerializer, addressAliasDeserializer);

		MerkleTrie<Bytes, BipState> bipStateTrie = loadSubTrie(
				mainTrie, KEY_BIP_STATE, nodeLoader, bipStateSerializer, bipStateDeserializer);

		MerkleTrie<Bytes, NetworkParamsState> networkParamsTrie = loadSubTrie(
				mainTrie, KEY_NETWORK_PARAMS, nodeLoader, networkParamsSerializer, networkParamsDeserializer);

		MerkleTrie<Bytes, TokenState> tokenTrie = loadSubTrie(
				mainTrie, KEY_TOKEN, nodeLoader, tokenSerializer, tokenDeserializer);

		return new WorldState(
				isMining, // <--- New Flag passed here
				rocksDBMerkleStorage,
				mainTrie,
				balanceTrie,
				nonceTrie,
				authorityTrie,
				addressAliasTrie,
				bipStateTrie,
				networkParamsTrie,
				tokenTrie);
	}

	private <T> MerkleTrie<Bytes, T> loadSubTrie(
			MerkleTrie<Bytes, Bytes> mainTrie,
			Bytes subTrieKey,
			NodeLoader loader,
			Function<T, Bytes> serializer,
			Function<Bytes, T> deserializer) {
		Bytes32 rootHash = mainTrie.get(subTrieKey)
				.map(Bytes32::wrap)
				.orElse(MerkleTrie.EMPTY_TRIE_NODE_HASH);
		StoredNodeFactory<T> factory = new StoredNodeFactory<>(loader, serializer, deserializer);
		return new StoredMerklePatriciaTrie<>(factory, rootHash);
	}
}