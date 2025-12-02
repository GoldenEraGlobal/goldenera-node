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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.springframework.modulith.NamedInterface;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.NodeUpdater;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorage;
import global.goldenera.node.shared.consensus.state.AccountBalanceState;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import global.goldenera.node.shared.consensus.state.AddressAliasState;
import global.goldenera.node.shared.consensus.state.AuthorityState;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.consensus.state.StateDiff;
import global.goldenera.node.shared.consensus.state.TokenState;
import global.goldenera.node.shared.consensus.state.impl.AccountBalanceStateImpl;
import global.goldenera.node.shared.consensus.state.impl.AccountNonceStateImpl;
import global.goldenera.node.shared.consensus.state.impl.AddressAliasStateImpl;
import global.goldenera.node.shared.consensus.state.impl.AuthorityStateImpl;
import global.goldenera.node.shared.consensus.state.impl.BipStateImpl;
import global.goldenera.node.shared.consensus.state.impl.NetworkParamsStateImpl;
import global.goldenera.node.shared.consensus.state.impl.TokenStateImpl;
import global.goldenera.node.shared.datatypes.BalanceKey;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Represents the mutable state of the blockchain at a specific point in time.
 * <p>
 * <b>Optimization Modes:</b>
 * <ul>
 * <li><b>Mining Mode (isMining=true):</b> Enables Journal/Rollback for skipping
 * invalid txs. Disables Initial State capture (saves memory).</li>
 * <li><b>Validation Mode (isMining=false):</b> Disables Journal (fail-fast,
 * saves GC pressure). Enables Initial State capture (needed for
 * Explorer/Diffs).</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@NamedInterface("world-state")
public class WorldState {

	// MODE FLAG
	boolean isMining;

	RocksDBMerkleStorage trieStorage;

	// Main Trie stores roots of Sub-Tries
	MerkleTrie<Bytes, Bytes> mainTrie;

	// Sub-Tries
	MerkleTrie<Bytes, AccountBalanceState> balanceTrie;
	MerkleTrie<Bytes, AccountNonceState> nonceTrie;
	MerkleTrie<Bytes, AuthorityState> authorityTrie;
	MerkleTrie<Bytes, AddressAliasState> addressAliasTrie;
	MerkleTrie<Bytes, BipState> bipStateTrie;
	MerkleTrie<Bytes, NetworkParamsState> networkParamsTrie;
	MerkleTrie<Bytes, TokenState> tokenTrie;

	// Dirty Caches (Mutable state overlay)
	Map<BalanceKey, AccountBalanceState> dirtyBalances = new HashMap<>();
	Map<Address, AccountNonceState> dirtyNonces = new HashMap<>();
	Map<Address, AuthorityState> dirtyAuthorities = new HashMap<>();
	Map<String, AddressAliasState> dirtyAddressAliases = new HashMap<>();
	Map<Hash, BipState> dirtyBipStates = new HashMap<>();
	Map<Address, TokenState> dirtyTokens = new HashMap<>();

	// Initial States (Only populated if isMining == false)
	Map<BalanceKey, AccountBalanceState> initialBalances = new HashMap<>();
	Map<Address, AccountNonceState> initialNonces = new HashMap<>();
	Map<Address, AuthorityState> initialAuthorities = new HashMap<>();
	Map<String, AddressAliasState> initialAddressAliases = new HashMap<>();
	Map<Hash, BipState> initialBipStates = new HashMap<>();
	Map<Address, TokenState> initialTokens = new HashMap<>();
	NetworkParamsState[] initialNetworkParams = new NetworkParamsState[1];

	// Set of changes for validation logic & deletions
	Set<Address> tokensCreatedThisBlock = new LinkedHashSet<>();
	Set<Address> authoritiesRemoved = new LinkedHashSet<>();
	Set<String> aliasesRemoved = new LinkedHashSet<>();

	// Dirty Cache for Singleton (Params)
	final NetworkParamsState[] dirtyParams = new NetworkParamsState[1];
	final boolean[] paramsChanged = { false };

	// =================================================================================
	// === JOURNAL-BASED ROLLBACK (OPTIMIZED) ===
	// =================================================================================

	private static final byte UNDO_MAP_PUT = 1;
	private static final byte UNDO_MAP_REMOVE = 2;
	private static final byte UNDO_SET_ADD = 3;
	private static final byte UNDO_SET_REMOVE = 4;
	private static final byte UNDO_ARRAY_SLOT = 5;
	private static final byte UNDO_BOOLEAN_SLOT = 6;

	private static class UndoRecord {
		byte type;
		Object target;
		Object key;
		Object value;

		@SuppressWarnings({ "unchecked", "rawtypes" })
		void apply() {
			switch (type) {
				case UNDO_MAP_PUT:
					((Map) target).put(key, value);
					break;
				case UNDO_MAP_REMOVE:
					((Map) target).remove(key);
					break;
				case UNDO_SET_ADD:
					((Set) target).add(key);
					break;
				case UNDO_SET_REMOVE:
					((Set) target).remove(key);
					break;
				case UNDO_ARRAY_SLOT:
					((Object[]) target)[0] = value;
					break;
				case UNDO_BOOLEAN_SLOT:
					((boolean[]) target)[0] = (Boolean) value;
					break;
			}
		}
	}

	// Journal is only used if isMining == true
	List<UndoRecord> journal = new ArrayList<>();

	public Object createSnapshot() {
		// Optimization: In Validation Mode, we don't rollback individual txs, so no
		// snapshot needed.
		if (!isMining)
			return -1;
		return journal.size();
	}

	public void revertToSnapshot(Object snapshotObj) {
		if (!isMining) {
			// In validation mode, we should never call revert because we fail-fast on the
			// first error.
			throw new UnsupportedOperationException("Rollbacks are not supported in Validation Mode (Fail-Fast)");
		}

		if (!(snapshotObj instanceof Integer)) {
			throw new IllegalArgumentException("Invalid snapshot object type");
		}
		int targetSize = (Integer) snapshotObj;
		if (targetSize > journal.size()) {
			throw new IllegalArgumentException("Cannot revert to a future state");
		}

		for (int i = journal.size() - 1; i >= targetSize; i--) {
			journal.get(i).apply();
		}

		if (journal.size() > targetSize) {
			journal.subList(targetSize, journal.size()).clear();
		}
	}

	// --- Journal Helpers (Conditional Execution) ---

	private <K, V> void recordMapChange(Map<K, V> map, K key) {
		if (!isMining)
			return; // GC Optimization: Skip object allocation in Validation Mode

		UndoRecord rec = new UndoRecord();
		rec.target = map;
		rec.key = key;
		if (map.containsKey(key)) {
			rec.type = UNDO_MAP_PUT;
			rec.value = map.get(key);
		} else {
			rec.type = UNDO_MAP_REMOVE;
		}
		journal.add(rec);
	}

	private <T> void recordSetChange(Set<T> set, T item) {
		if (!isMining)
			return; // GC Optimization

		UndoRecord rec = new UndoRecord();
		rec.target = set;
		rec.key = item;
		if (set.contains(item)) {
			rec.type = UNDO_SET_ADD;
		} else {
			rec.type = UNDO_SET_REMOVE;
		}
		journal.add(rec);
	}

	// --- Initial State Capture (Conditional Execution) ---

	private <K, V> void captureInitialState(Map<K, V> initialMap, Map<K, V> dirtyMap, K key, Function<K, V> loader) {
		if (isMining)
			return; // Memory Optimization: Miner doesn't need diffs for explorer

		if (!initialMap.containsKey(key)) {
			if (!dirtyMap.containsKey(key)) {
				V stateFromTrie = loader.apply(key);
				initialMap.put(key, stateFromTrie);
			}
		}
	}

	// ... (computeDiff method remains the same) ...
	private <K, V> Map<K, StateDiff<V>> computeDiff(Map<K, V> dirtyMap, Map<K, V> initialMap, V zeroValue) {
		// Safety check: Diffs are only available in Validation Mode
		if (isMining)
			return new HashMap<>();

		Map<K, StateDiff<V>> diffs = new HashMap<>(dirtyMap.size());
		dirtyMap.forEach((key, newValue) -> {
			V oldValue = initialMap.getOrDefault(key, zeroValue);
			diffs.put(key, new WorldStateDiff<>(oldValue, newValue));
		});
		return diffs;
	}

	// =================================================================================
	// === STATE ACCESS API (Updated with Optimizations) ===
	// =================================================================================

	public AccountNonceState getNonce(Address address) {
		if (dirtyNonces.containsKey(address))
			return dirtyNonces.get(address);
		return nonceTrie.get(address).orElse(AccountNonceStateImpl.ZERO);
	}

	public void setNonce(Address address, AccountNonceState state) {
		captureInitialState(initialNonces, dirtyNonces, address, this::getNonce);
		recordMapChange(dirtyNonces, address);
		dirtyNonces.put(address, state);
	}

	public AccountBalanceState getBalance(Address address, Address tokenAddress) {
		BalanceKey balanceKey = new BalanceKey(address, tokenAddress);
		if (dirtyBalances.containsKey(balanceKey))
			return dirtyBalances.get(balanceKey);

		Bytes merkleKey = getBalanceKey(address, tokenAddress);
		return balanceTrie.get(merkleKey).orElse(AccountBalanceStateImpl.ZERO);
	}

	public void setBalance(Address address, Address tokenAddress, AccountBalanceState state) {
		BalanceKey balanceKey = new BalanceKey(address, tokenAddress);
		captureInitialState(initialBalances, dirtyBalances, balanceKey,
				(k) -> getBalance(k.getAddress(), k.getTokenAddress()));
		recordMapChange(dirtyBalances, balanceKey);
		dirtyBalances.put(balanceKey, state);
	}

	public AuthorityState getAuthority(Address address) {
		if (dirtyAuthorities.containsKey(address))
			return dirtyAuthorities.get(address);
		if (authoritiesRemoved.contains(address))
			return AuthorityStateImpl.ZERO;
		return authorityTrie.get(address).orElse(AuthorityStateImpl.ZERO);
	}

	public void addAuthority(Address address, AuthorityState state) {
		captureInitialState(initialAuthorities, dirtyAuthorities, address, this::getAuthority);
		recordMapChange(dirtyAuthorities, address);
		dirtyAuthorities.put(address, state);

		recordSetChange(authoritiesRemoved, address);
		authoritiesRemoved.remove(address);
	}

	public void removeAuthority(Address address) {
		captureInitialState(initialAuthorities, dirtyAuthorities, address, this::getAuthority);
		recordMapChange(dirtyAuthorities, address);
		dirtyAuthorities.remove(address);

		recordSetChange(authoritiesRemoved, address);
		authoritiesRemoved.add(address);
	}

	public TokenState getToken(Address address) {
		if (dirtyTokens.containsKey(address))
			return dirtyTokens.get(address);
		return tokenTrie.get(address).orElse(TokenStateImpl.ZERO);
	}

	public void setToken(Address address, TokenState state) {
		captureInitialState(initialTokens, dirtyTokens, address, this::getToken);
		recordMapChange(dirtyTokens, address);
		dirtyTokens.put(address, state);
	}

	public BipState getBip(Hash hash) {
		if (dirtyBipStates.containsKey(hash))
			return dirtyBipStates.get(hash);
		return bipStateTrie.get(hash).orElse(BipStateImpl.ZERO);
	}

	public void setBip(Hash hash, BipState state) {
		captureInitialState(initialBipStates, dirtyBipStates, hash, this::getBip);
		recordMapChange(dirtyBipStates, hash);
		dirtyBipStates.put(hash, state);
	}

	public AddressAliasState getAddressAlias(String alias) {
		if (dirtyAddressAliases.containsKey(alias))
			return dirtyAddressAliases.get(alias);
		if (aliasesRemoved.contains(alias))
			return AddressAliasStateImpl.ZERO;
		return addressAliasTrie.get(Bytes.wrap(alias.getBytes(StandardCharsets.UTF_8)))
				.orElse(AddressAliasStateImpl.ZERO);
	}

	public void addAddressAlias(String alias, AddressAliasState state) {
		captureInitialState(initialAddressAliases, dirtyAddressAliases, alias, this::getAddressAlias);
		recordMapChange(dirtyAddressAliases, alias);
		dirtyAddressAliases.put(alias, state);

		recordSetChange(aliasesRemoved, alias);
		aliasesRemoved.remove(alias);
	}

	public void removeAddressAlias(String alias) {
		captureInitialState(initialAddressAliases, dirtyAddressAliases, alias, this::getAddressAlias);
		recordMapChange(dirtyAddressAliases, alias);
		dirtyAddressAliases.remove(alias);

		recordSetChange(aliasesRemoved, alias);
		aliasesRemoved.add(alias);
	}

	public NetworkParamsState getParams() {
		if (dirtyParams[0] != null)
			return dirtyParams[0];
		return networkParamsTrie.get(WorldStateFactory.KEY_NETWORK_PARAMS).orElse(NetworkParamsStateImpl.ZERO);
	}

	public void setParams(NetworkParamsState state) {
		if (!isMining) {
			if (initialNetworkParams[0] == null && dirtyParams[0] == null) {
				initialNetworkParams[0] = getParams();
			}
		}

		if (isMining) {
			UndoRecord rec = new UndoRecord();
			rec.type = UNDO_ARRAY_SLOT;
			rec.target = dirtyParams;
			rec.value = dirtyParams[0];
			journal.add(rec);
		}
		dirtyParams[0] = state;
	}

	public void markParamsAsChanged() {
		if (isMining) {
			UndoRecord rec = new UndoRecord();
			rec.type = UNDO_BOOLEAN_SLOT;
			rec.target = paramsChanged;
			rec.value = paramsChanged[0];
			journal.add(rec);
		}
		this.paramsChanged[0] = true;
	}

	public boolean isParamsChangedThisBlock() {
		return this.paramsChanged[0];
	}

	public boolean checkAndMarkTokenAsUpdated(Address tokenAddress) {
		recordSetChange(tokensCreatedThisBlock, tokenAddress);
		return tokensCreatedThisBlock.add(tokenAddress);
	}

	// =================================================================================
	// === COMMIT & STORAGE (Untouched logic) ===
	// =================================================================================

	private void applyChangesToTries() {
		for (Map.Entry<BalanceKey, AccountBalanceState> entry : dirtyBalances.entrySet()) {
			BalanceKey key = entry.getKey();
			balanceTrie.put(getBalanceKey(key.getAddress(), key.getTokenAddress()), entry.getValue());
		}
		dirtyNonces.forEach(nonceTrie::put);
		authoritiesRemoved.forEach(authorityTrie::remove);
		dirtyAuthorities.forEach(authorityTrie::put);
		aliasesRemoved.forEach(alias -> addressAliasTrie.remove(Bytes.wrap(alias.getBytes(StandardCharsets.UTF_8))));
		dirtyAddressAliases.forEach(
				(alias, state) -> addressAliasTrie.put(Bytes.wrap(alias.getBytes(StandardCharsets.UTF_8)), state));
		dirtyBipStates.forEach(bipStateTrie::put);
		dirtyTokens.forEach(tokenTrie::put);
		if (dirtyParams[0] != null) {
			networkParamsTrie.put(WorldStateFactory.KEY_NETWORK_PARAMS, dirtyParams[0]);
		}
	}

	public Hash getFinalStateRoot() {
		return Hash.wrap(mainTrie.getRootHash());
	}

	public Hash calculateRootHash() {
		NodeUpdater nodeUpdater = trieStorage::put;
		applyChangesToTries();
		balanceTrie.commit(nodeUpdater);
		nonceTrie.commit(nodeUpdater);
		authorityTrie.commit(nodeUpdater);
		addressAliasTrie.commit(nodeUpdater);
		bipStateTrie.commit(nodeUpdater);
		networkParamsTrie.commit(nodeUpdater);
		tokenTrie.commit(nodeUpdater);

		mainTrie.put(WorldStateFactory.KEY_BALANCE, balanceTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_NONCE, nonceTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_AUTHORITY, authorityTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_ADDRESS_ALIAS, addressAliasTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_BIP_STATE, bipStateTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_NETWORK_PARAMS, networkParamsTrie.getRootHash());
		mainTrie.put(WorldStateFactory.KEY_TOKEN, tokenTrie.getRootHash());

		mainTrie.commit(nodeUpdater);
		return getFinalStateRoot();
	}

	public void persistToBatch(WriteBatch batch) throws RocksDBException {
		calculateRootHash();
		trieStorage.commitToBatch(batch);
	}

	public void rollback() {
		dirtyBalances.clear();
		dirtyNonces.clear();
		dirtyAuthorities.clear();
		dirtyAddressAliases.clear();
		dirtyBipStates.clear();
		dirtyTokens.clear();

		initialBalances.clear();
		initialNonces.clear();
		initialAuthorities.clear();
		initialAddressAliases.clear();
		initialBipStates.clear();
		initialTokens.clear();
		initialNetworkParams[0] = null;

		dirtyParams[0] = null;
		paramsChanged[0] = false;

		tokensCreatedThisBlock.clear();
		authoritiesRemoved.clear();
		aliasesRemoved.clear();

		journal.clear();
		trieStorage.rollback();
	}

	/**
	 * Clears dirty caches and tracking sets to prepare for the next block execution
	 * on top of the current state (which accumulates in the Trie memory).
	 * Used specifically for multi-block processing (Reorgs/Replay).
	 */
	public void prepareForNextBlock() {
		dirtyBalances.clear();
		dirtyNonces.clear();
		dirtyAuthorities.clear();
		dirtyAddressAliases.clear();
		dirtyBipStates.clear();
		dirtyTokens.clear();

		initialBalances.clear();
		initialNonces.clear();
		initialAuthorities.clear();
		initialAddressAliases.clear();
		initialBipStates.clear();
		initialTokens.clear();
		initialNetworkParams[0] = null;

		dirtyParams[0] = null;
		paramsChanged[0] = false;

		tokensCreatedThisBlock.clear();
		authoritiesRemoved.clear();
		aliasesRemoved.clear();

		journal.clear();
	}

	private Bytes getBalanceKey(Address address, Address tokenAddress) {
		return Hash.hash(Bytes.concatenate(address, tokenAddress));
	}

	// --- Diffs (Only valid in Validation Mode) ---

	public Map<BalanceKey, StateDiff<AccountBalanceState>> getBalanceDiffs() {
		return computeDiff(dirtyBalances, initialBalances, AccountBalanceStateImpl.ZERO);
	}

	public Map<Address, StateDiff<AccountNonceState>> getNonceDiffs() {
		return computeDiff(dirtyNonces, initialNonces, AccountNonceStateImpl.ZERO);
	}

	public Map<Address, StateDiff<TokenState>> getTokenDiffs() {
		return computeDiff(dirtyTokens, initialTokens, TokenStateImpl.ZERO);
	}

	public Map<Hash, StateDiff<BipState>> getBipDiffs() {
		return computeDiff(dirtyBipStates, initialBipStates, BipStateImpl.ZERO);
	}

	public StateDiff<NetworkParamsState> getParamsDiff() {
		if (isMining) {
			return null;
		}

		if (dirtyParams[0] == null) {
			return null;
		}

		NetworkParamsState oldVal = initialNetworkParams[0] != null ? initialNetworkParams[0]
				: NetworkParamsStateImpl.ZERO;

		NetworkParamsState newVal = dirtyParams[0];

		if (oldVal.equals(newVal)) {
			return null;
		}

		return new WorldStateDiff<>(oldVal, newVal);
	}

	public Map<String, AddressAliasState> getAliasesRemovedWithState() {
		if (isMining)
			return new HashMap<>();
		Map<String, AddressAliasState> result = new HashMap<>();
		for (String alias : aliasesRemoved) {
			AddressAliasState oldState = initialAddressAliases.get(alias);
			if (oldState != null) {
				result.put(alias, oldState);
			}
		}
		return result;
	}

	public Map<Address, AuthorityState> getAuthoritiesRemovedWithState() {
		if (isMining)
			return new HashMap<>();
		Map<Address, AuthorityState> result = new HashMap<>();
		for (Address addr : authoritiesRemoved) {
			AuthorityState oldState = initialAuthorities.get(addr);
			if (oldState != null) {
				result.put(addr, oldState);
			}
		}
		return result;
	}
}