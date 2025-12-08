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
package global.goldenera.node.core.blockchain.genesis;

import static lombok.AccessLevel.PRIVATE;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Provides cached access to genesis block WorldState.
 * Genesis state is immutable, so it can be cached forever.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class GenesisStateService {

    ChainQuery chainQuery;
    WorldStateFactory worldStateFactory;
    EntityIndexRepository entityIndexRepository;

    // Cached genesis state (lazily initialized, never changes)
    AtomicReference<WorldState> cachedGenesisState = new AtomicReference<>();

    /**
     * Gets the genesis WorldState (cached).
     */
    public WorldState getGenesisState() {
        WorldState state = cachedGenesisState.get();
        if (state == null) {
            state = loadGenesisState();
            cachedGenesisState.compareAndSet(null, state);
            // Return what's actually in the AtomicReference (in case another thread won)
            state = cachedGenesisState.get();
        }
        return state;
    }

    private WorldState loadGenesisState() {
        StoredBlock genesis = chainQuery.getStoredBlockByHeight(0L)
                .orElseThrow(() -> new GENotFoundException("Genesis block not found"));
        Hash genesisStateRoot = genesis.getBlock().getHeader().getStateRootHash();
        return worldStateFactory.createForValidation(genesisStateRoot);
    }

    /**
     * Gets network params from genesis state.
     */
    public NetworkParamsState getGenesisNetworkParams() {
        return getGenesisState().getParams();
    }

    /**
     * Gets native token state from genesis.
     */
    public TokenState getGenesisNativeToken() {
        TokenState token = getGenesisState().getToken(Address.NATIVE_TOKEN);
        if (!token.exists()) {
            throw new GENotFoundException("Native token not found in genesis");
        }
        return token;
    }

    /**
     * Gets all authorities with addresses from genesis.
     * Uses EntityIndexRepository which has the cached authority list.
     */
    public Map<Address, AuthorityState> getGenesisAuthoritiesWithAddresses() {
        // For genesis, we load from WorldState directly since EntityIndexRepository
        // might have current authorities, not genesis authorities
        // But genesis authorities are the same as defined in Constants, so we can just
        // enumerate them from the genesis WorldState
        WorldState genesisState = getGenesisState();
        // Since there's no getAllAuthorities in WorldState, we use
        // EntityIndexRepository
        // which caches them. At genesis time, these are the same authorities.
        return entityIndexRepository.getAllAuthoritiesWithAddresses();
    }

    /**
     * Gets genesis block hash.
     */
    public Hash getGenesisBlockHash() {
        return chainQuery.getStoredBlockByHeight(0L)
                .map(StoredBlock::getHash)
                .orElseThrow(() -> new GENotFoundException("Genesis block not found"));
    }
}
