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
package global.goldenera.node.core.storage.blockchain;

import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.state.WorldState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Getter
@Repository
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class EntityIndexRepository {

    RocksDBRepository rocksDBRepository;
    ObjectMapper objectMapper;
    Cache<String, List<TokenState>> tokensCache;
    Cache<String, List<AuthorityState>> authoritiesCache;
    Cache<String, Map<Address, TokenState>> tokensMapCache;
    Cache<String, Map<Address, AuthorityState>> authoritiesMapCache;

    // --- SAVE / UPDATE ---

    public void saveEntities(WriteBatch batch, Block block, WorldState worldState) {
        try {
            List<UndoAction> undoLog = new ArrayList<>();

            if (!worldState.isMining()) {
                // --- VALIDATION MODE (Use Diffs - Optimized) ---

                // 1. TOKENS
                for (Map.Entry<Address, StateDiff<TokenState>> entry : worldState.getTokenDiffs().entrySet()) {
                    Address address = entry.getKey();
                    StateDiff<TokenState> diff = entry.getValue();

                    // Old Value (Undo Log)
                    byte[] oldValueBytes = null;
                    if (diff.getOldValue() != null && diff.getOldValue().exists()) {
                        oldValueBytes = objectMapper.writeValueAsBytes(diff.getOldValue());
                    }
                    undoLog.add(new UndoAction(UndoType.TOKEN, address.toHexString(), oldValueBytes));

                    // New Value (DB)
                    if (diff.getNewValue() != null && diff.getNewValue().exists()) {
                        byte[] newValueBytes = objectMapper.writeValueAsBytes(diff.getNewValue());
                        batch.put(rocksDBRepository.getColumnFamilies().tokens(), address.toArray(), newValueBytes);
                    } else {
                        batch.delete(rocksDBRepository.getColumnFamilies().tokens(), address.toArray());
                    }
                }

                // 2. AUTHORITIES

                for (Map.Entry<Address, AuthorityState> entry : worldState.getDirtyAuthorities().entrySet()) {
                    Address address = entry.getKey();
                    AuthorityState newState = entry.getValue();
                    byte[] key = address.toArray();

                    byte[] oldValueBytes = rocksDBRepository.get(rocksDBRepository.getColumnFamilies().authorities(),
                            key);
                    undoLog.add(new UndoAction(UndoType.AUTHORITY, address.toHexString(), oldValueBytes));

                    byte[] newValueBytes = objectMapper.writeValueAsBytes(newState);
                    batch.put(rocksDBRepository.getColumnFamilies().authorities(), key, newValueBytes);
                }

                // Removed - Use cached state
                for (Map.Entry<Address, AuthorityState> entry : worldState.getAuthoritiesRemovedWithState()
                        .entrySet()) {
                    Address address = entry.getKey();
                    AuthorityState oldState = entry.getValue();
                    byte[] key = address.toArray();

                    byte[] oldValueBytes = objectMapper.writeValueAsBytes(oldState);
                    undoLog.add(new UndoAction(UndoType.AUTHORITY, address.toHexString(), oldValueBytes));

                    batch.delete(rocksDBRepository.getColumnFamilies().authorities(), key);
                }

            } else {
                // --- MINING MODE (No Diffs - Fallback to DB Reads) ---

                // 1. TOKENS
                for (Map.Entry<Address, TokenState> entry : worldState.getDirtyTokens().entrySet()) {
                    Address address = entry.getKey();
                    TokenState newState = entry.getValue();
                    byte[] key = address.toArray();

                    // Read old value
                    byte[] oldValueBytes = rocksDBRepository.get(rocksDBRepository.getColumnFamilies().tokens(), key);

                    // Add to undo log
                    undoLog.add(new UndoAction(UndoType.TOKEN, address.toHexString(), oldValueBytes));

                    // Write new value
                    byte[] newValueBytes = objectMapper.writeValueAsBytes(newState);
                    batch.put(rocksDBRepository.getColumnFamilies().tokens(), key, newValueBytes);
                }

                // 2. AUTHORITIES
                // Dirty authorities (added/updated)
                for (Map.Entry<Address, AuthorityState> entry : worldState.getDirtyAuthorities().entrySet()) {
                    Address address = entry.getKey();
                    AuthorityState newState = entry.getValue();
                    byte[] key = address.toArray();

                    byte[] oldValueBytes = rocksDBRepository.get(rocksDBRepository.getColumnFamilies().authorities(),
                            key);
                    undoLog.add(new UndoAction(UndoType.AUTHORITY, address.toHexString(), oldValueBytes));

                    byte[] newValueBytes = objectMapper.writeValueAsBytes(newState);
                    batch.put(rocksDBRepository.getColumnFamilies().authorities(), key, newValueBytes);
                }

                // Removed authorities
                for (Address address : worldState.getAuthoritiesRemoved()) {
                    byte[] key = address.toArray();
                    byte[] oldValueBytes = rocksDBRepository.get(rocksDBRepository.getColumnFamilies().authorities(),
                            key);
                    undoLog.add(new UndoAction(UndoType.AUTHORITY, address.toHexString(), oldValueBytes));

                    batch.delete(rocksDBRepository.getColumnFamilies().authorities(), key);
                }
            }

            // Save Undo Log
            if (!undoLog.isEmpty()) {
                byte[] undoLogBytes = objectMapper.writeValueAsBytes(undoLog);
                batch.put(rocksDBRepository.getColumnFamilies().entityUndoLog(), block.getHash().toArray(),
                        undoLogBytes);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to update entity index", e);
        }
    }

    // --- REVERT ---

    public void revertEntities(WriteBatch batch, Block block) {
        try {
            byte[] undoLogBytes = rocksDBRepository.get(rocksDBRepository.getColumnFamilies().entityUndoLog(),
                    block.getHash().toArray());
            if (undoLogBytes == null) {
                return; // Nothing to revert
            }

            UndoAction[] undoLog = objectMapper.readValue(undoLogBytes, UndoAction[].class);

            for (UndoAction action : undoLog) {
                Address address = Address.fromHexString(action.address);
                if (action.type == UndoType.TOKEN) {
                    if (action.oldValue == null) {
                        batch.delete(rocksDBRepository.getColumnFamilies().tokens(), address.toArray());
                    } else {
                        batch.put(rocksDBRepository.getColumnFamilies().tokens(), address.toArray(), action.oldValue);
                    }
                } else if (action.type == UndoType.AUTHORITY) {
                    if (action.oldValue == null) {
                        batch.delete(rocksDBRepository.getColumnFamilies().authorities(), address.toArray());
                    } else {
                        batch.put(rocksDBRepository.getColumnFamilies().authorities(), address.toArray(),
                                action.oldValue);
                    }
                }
            }

            // Delete undo log
            batch.delete(rocksDBRepository.getColumnFamilies().entityUndoLog(), block.getHash().toArray());

        } catch (Exception e) {
            throw new RuntimeException("Failed to revert entity index", e);
        }
    }

    // --- READ ---

    public List<TokenState> getAllTokens() {
        List<TokenState> cached = tokensCache.getIfPresent("ALL");
        if (cached != null) {
            return cached;
        }

        List<TokenState> tokens = new ArrayList<>();
        try (RocksIterator it = rocksDBRepository.newIterator(rocksDBRepository.getColumnFamilies().tokens())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                tokens.add(objectMapper.readValue(it.value(), TokenStateImpl.class));
            }
        } catch (IOException e) {
            log.error("Failed to read token", e);
        }

        tokensCache.put("ALL", tokens);
        return tokens;
    }

    public List<AuthorityState> getAllAuthorities() {
        List<AuthorityState> cached = authoritiesCache.getIfPresent("ALL");
        if (cached != null) {
            return cached;
        }

        List<AuthorityState> authorities = new ArrayList<>();
        try (RocksIterator it = rocksDBRepository.newIterator(rocksDBRepository.getColumnFamilies().authorities())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                authorities.add(objectMapper.readValue(it.value(), AuthorityStateImpl.class));
            }
        } catch (IOException e) {
            log.error("Failed to read authority", e);
        }

        authoritiesCache.put("ALL", authorities);
        return authorities;
    }

    public Map<Address, TokenState> getAllTokensWithAddresses() {
        Map<Address, TokenState> cached = tokensMapCache.getIfPresent("ALL");
        if (cached != null) {
            return cached;
        }

        Map<Address, TokenState> tokens = new LinkedHashMap<>();
        try (RocksIterator it = rocksDBRepository.newIterator(rocksDBRepository.getColumnFamilies().tokens())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                Address address = Address.wrap(it.key());
                TokenState state = objectMapper.readValue(it.value(), TokenStateImpl.class);
                tokens.put(address, state);
            }
        } catch (IOException e) {
            log.error("Failed to read tokens with addresses", e);
        }

        tokensMapCache.put("ALL", tokens);
        return tokens;
    }

    public Map<Address, AuthorityState> getAllAuthoritiesWithAddresses() {
        Map<Address, AuthorityState> cached = authoritiesMapCache.getIfPresent("ALL");
        if (cached != null) {
            return cached;
        }

        Map<Address, AuthorityState> authorities = new LinkedHashMap<>();
        try (RocksIterator it = rocksDBRepository.newIterator(rocksDBRepository.getColumnFamilies().authorities())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                Address address = Address.wrap(it.key());
                AuthorityState state = objectMapper.readValue(it.value(), AuthorityStateImpl.class);
                authorities.put(address, state);
            }
        } catch (IOException e) {
            log.error("Failed to read authorities with addresses", e);
        }

        authoritiesMapCache.put("ALL", authorities);
        return authorities;
    }

    public void invalidateCaches() {
        tokensCache.invalidateAll();
        authoritiesCache.invalidateAll();
        tokensMapCache.invalidateAll();
        authoritiesMapCache.invalidateAll();
    }

    // --- INNER CLASSES ---

    public enum UndoType {
        TOKEN, AUTHORITY
    }

    public static class UndoAction {
        public UndoType type;
        public String address;
        public byte[] oldValue; // null if it didn't exist

        public UndoAction() {
        }

        public UndoAction(UndoType type, String address, byte[] oldValue) {
            this.type = type;
            this.address = address;
            this.oldValue = oldValue;
        }
    }
}
