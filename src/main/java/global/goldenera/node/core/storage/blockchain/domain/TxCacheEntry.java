package global.goldenera.node.core.storage.blockchain.domain;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import lombok.Builder;

/**
 * Cached transaction with its block metadata.
 * Optimized to avoid loading full StoredBlock and to provide O(1) access to
 * metadata.
 */
@Builder
public record TxCacheEntry(
        Tx tx,
        Hash blockHash,
        long blockHeight,
        long blockTimestamp,
        int txIndex,
        Address sender,
        int size) {
}
