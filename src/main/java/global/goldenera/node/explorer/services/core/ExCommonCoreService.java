package global.goldenera.node.explorer.services.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExAccountBalance;
import global.goldenera.node.explorer.entities.ExAddressAlias;
import global.goldenera.node.explorer.entities.ExAuthority;
import global.goldenera.node.explorer.entities.ExBlockHeader;
import global.goldenera.node.explorer.entities.ExMemTransfer;
import global.goldenera.node.explorer.entities.ExToken;
import global.goldenera.node.explorer.entities.ExTx;
import global.goldenera.node.explorer.entities.ExValidator;
import global.goldenera.node.explorer.enums.ExSearchEntityType;
import global.goldenera.node.explorer.repositories.ExAccountBalanceRepository;
import global.goldenera.node.explorer.repositories.ExAddressAliasRepository;
import global.goldenera.node.explorer.repositories.ExAuthorityRepository;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.repositories.ExMemTransferRepository;
import global.goldenera.node.explorer.repositories.ExTokenRepository;
import global.goldenera.node.explorer.repositories.ExTxRepository;
import global.goldenera.node.explorer.repositories.ExValidatorRepository;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ExCommonCoreService {

    ExBlockHeaderRepository blockHeaderRepository;
    ExTxRepository txRepository;
    ExAccountBalanceRepository accountBalanceRepository;
    ExTokenRepository tokenRepository;
    ExAddressAliasRepository addressAliasRepository;
    ExValidatorRepository validatorRepository;
    ExAuthorityRepository authorityRepository;
    ExMemTransferRepository memTransferRepository;

    public ExSearchResult search(@NonNull String query, Set<ExSearchEntityType> searchIn) {
        query = query.trim().toLowerCase();
        if (query.isEmpty() || query.isBlank() || query.length() > 256 || query.length() < 1) {
            if (!query.matches("\\d+")) {
                return ExSearchResult.builder().build();
            }
        }

        String q = query.trim();
        if (searchIn == null || searchIn.isEmpty()) {
            searchIn = Set.of(ExSearchEntityType.values());
        }

        // Futures
        CompletableFuture<List<ExBlockHeader>> blocksFuture = CompletableFuture
                .completedFuture(Collections.emptyList());
        CompletableFuture<List<ExTx>> txsFuture = CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<ExMemTransfer>> mempoolFuture = CompletableFuture
                .completedFuture(Collections.emptyList());
        CompletableFuture<List<ExToken>> tokensFuture = CompletableFuture.completedFuture(Collections.emptyList());
        CompletableFuture<List<ExAccountBalance>> accountsFuture = CompletableFuture
                .completedFuture(Collections.emptyList());
        CompletableFuture<List<ExAddressAlias>> aliasesFuture = CompletableFuture
                .completedFuture(Collections.emptyList());
        CompletableFuture<List<ExValidator>> validatorsFuture = CompletableFuture
                .completedFuture(Collections.emptyList());
        CompletableFuture<List<ExAuthority>> authoritiesFuture = CompletableFuture
                .completedFuture(Collections.emptyList());

        boolean isHash32 = isHash32(q);
        boolean isAddress = isAddress(q);
        boolean isNumber = isNumber(q);

        // 1. Search Blocks
        if (searchIn.contains(ExSearchEntityType.BLOCK)) {
            blocksFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExBlockHeader> results = new java.util.HashSet<>();
                if (isNumber) {
                    try {
                        long height = Long.parseLong(q);
                        blockHeaderRepository.findByHeight(height).ifPresent(results::add);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
                if (isHash32) {
                    try {
                        Hash hash = Hash.fromHexString(q);
                        blockHeaderRepository.findById(new ExBlockHeader.ExBlockHeaderPK(hash)).ifPresent(results::add);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                return new ArrayList<>(results);
            });
        }

        // 2. Search Transactions (Confirmed)
        if (searchIn.contains(ExSearchEntityType.TRANSACTION)) {
            txsFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExTx> results = new java.util.HashSet<>();
                if (isHash32) {
                    try {
                        Hash hash = Hash.fromHexString(q);
                        txRepository.findById(new ExTx.ExTxPK(hash)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
                return new ArrayList<>(results);
            });
        }

        // 3. Search Mempool
        if (searchIn.contains(ExSearchEntityType.MEMPOOL)) {
            mempoolFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExMemTransfer> results = new java.util.HashSet<>();
                if (isHash32) {
                    try {
                        Hash hash = Hash.fromHexString(q);
                        memTransferRepository.findById(new ExMemTransfer.ExMemTransferPK(hash)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
                return new ArrayList<>(results);
            });
        }

        // 4. Search Tokens
        if (searchIn.contains(ExSearchEntityType.TOKEN)) {
            tokensFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExToken> results = new java.util.HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        tokenRepository.findById(new ExToken.ExTokenPK(addr)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
                results.addAll(tokenRepository.searchTokens(q)); // NATIVE QUERY
                return new ArrayList<>(results);
            });
        }

        // 5. Search Accounts & Aliases & Validators & Authorities
        if (searchIn.contains(ExSearchEntityType.ACCOUNT)) {
            accountsFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExAccountBalance> results = new java.util.HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        // Fetch all accounts by address (native query in repo)
                        results.addAll(accountBalanceRepository.findByAddress(addr.toArray()));
                    } catch (Exception e) {
                    }
                }
                return new ArrayList<>(results);
            });

            aliasesFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExAddressAlias> results = new java.util.HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        results.addAll(addressAliasRepository.findByAddress(addr));
                    } catch (Exception e) {
                    }
                }
                addressAliasRepository.findByAliasIgnoreCase(q).ifPresent(results::add);
                return new ArrayList<>(results);
            });
        }

        if (searchIn.contains(ExSearchEntityType.VALIDATOR)) {
            validatorsFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExValidator> results = new java.util.HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        validatorRepository.findById(new ExValidator.ValidatorPK(addr)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
                return new ArrayList<>(results);
            });
        }

        if (searchIn.contains(ExSearchEntityType.AUTHORITY)) {
            authoritiesFuture = CompletableFuture.supplyAsync(() -> {
                Set<ExAuthority> results = new java.util.HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        authorityRepository.findById(new ExAuthority.AuthorityPK(addr)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
                return new ArrayList<>(results);
            });
        }

        try {
            List<ExBlockHeader> blocks = blocksFuture.get();
            List<ExTx> transactions = txsFuture.get();
            List<ExMemTransfer> mempoolTransactions = mempoolFuture.get();
            List<ExToken> tokens = tokensFuture.get();
            List<ExAccountBalance> accounts = accountsFuture.get();
            List<ExAddressAlias> aliases = aliasesFuture.get();
            List<ExValidator> validators = validatorsFuture.get();
            List<ExAuthority> authorities = authoritiesFuture.get();

            long count = (long) blocks.size() + transactions.size() + mempoolTransactions.size() + tokens.size()
                    + accounts.size() + aliases.size() + validators.size() + authorities.size();

            return ExSearchResult.builder()
                    .blocks(blocks)
                    .transactions(transactions)
                    .mempoolTransactions(mempoolTransactions)
                    .tokens(tokens)
                    .accounts(accounts)
                    .aliases(aliases)
                    .validators(validators)
                    .authorities(authorities)
                    .count(count)
                    .build();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error executing search", e);
            Thread.currentThread().interrupt();
            throw new GEFailedException("Search failed", e);
        }
    }

    private boolean isHash32(String q) {
        return (q.length() == 64 || (q.startsWith("0x") && q.length() == 66)) && isHex(q);
    }

    private boolean isAddress(String q) {
        return (q.length() == 40 || (q.startsWith("0x") && q.length() == 42)) && isHex(q);
    }

    private boolean isNumber(String q) {
        return q.matches("\\d+");
    }

    private boolean isHex(String q) {
        String s = q.startsWith("0x") ? q.substring(2) : q;
        return s.matches("^[0-9a-fA-F]+$");
    }

    @Value
    @Builder
    public static class ExSearchResult {
        List<ExBlockHeader> blocks;
        List<ExTx> transactions;
        List<ExMemTransfer> mempoolTransactions;
        List<ExToken> tokens;
        List<ExAccountBalance> accounts;
        List<ExAddressAlias> aliases;
        List<ExValidator> validators;
        List<ExAuthority> authorities;
        long count;
    }
}
