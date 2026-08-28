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
package global.goldenera.node.explorer.services.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.config.ExplorerAsyncConfig;
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
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ExCommonCoreService {
	static final int MAX_QUERY_LENGTH = 256;
	static final int MIN_TEXT_QUERY_LENGTH = 2;
	static final int MAX_TEXT_SEARCH_RESULTS = 25;
	static final long SEARCH_TIMEOUT_MILLIS = 3_000L;

    ExBlockHeaderRepository blockHeaderRepository;
    ExTxRepository txRepository;
    ExAccountBalanceRepository accountBalanceRepository;
    ExTokenRepository tokenRepository;
    ExAddressAliasRepository addressAliasRepository;
    ExValidatorRepository validatorRepository;
    ExAuthorityRepository authorityRepository;
    ExMemTransferRepository memTransferRepository;
	Executor searchExecutor;
	long searchTimeoutMillis;

		@Autowired
		public ExCommonCoreService(
			ExBlockHeaderRepository blockHeaderRepository,
			ExTxRepository txRepository,
			ExAccountBalanceRepository accountBalanceRepository,
			ExTokenRepository tokenRepository,
			ExAddressAliasRepository addressAliasRepository,
			ExValidatorRepository validatorRepository,
			ExAuthorityRepository authorityRepository,
			ExMemTransferRepository memTransferRepository,
			@Qualifier(ExplorerAsyncConfig.EXPLORER_SEARCH_EXECUTOR) Executor searchExecutor) {
		this(blockHeaderRepository, txRepository, accountBalanceRepository, tokenRepository,
				addressAliasRepository, validatorRepository, authorityRepository, memTransferRepository,
				searchExecutor, SEARCH_TIMEOUT_MILLIS);
	}

	ExCommonCoreService(
			ExBlockHeaderRepository blockHeaderRepository,
			ExTxRepository txRepository,
			ExAccountBalanceRepository accountBalanceRepository,
			ExTokenRepository tokenRepository,
			ExAddressAliasRepository addressAliasRepository,
			ExValidatorRepository validatorRepository,
			ExAuthorityRepository authorityRepository,
			ExMemTransferRepository memTransferRepository,
			Executor searchExecutor,
			long searchTimeoutMillis) {
		this.blockHeaderRepository = blockHeaderRepository;
		this.txRepository = txRepository;
		this.accountBalanceRepository = accountBalanceRepository;
		this.tokenRepository = tokenRepository;
		this.addressAliasRepository = addressAliasRepository;
		this.validatorRepository = validatorRepository;
		this.authorityRepository = authorityRepository;
		this.memTransferRepository = memTransferRepository;
		this.searchExecutor = searchExecutor;
		this.searchTimeoutMillis = searchTimeoutMillis;
	}

    public ExSearchResult search(@NonNull String query, Set<ExSearchEntityType> searchIn) {
		String q = validateAndNormalizeQuery(query);
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
		boolean isTextQuery = !isHash32 && !isAddress;

        // 1. Search Blocks
        if (searchIn.contains(ExSearchEntityType.BLOCK) && (isNumber || isHash32)) {
            blocksFuture = async(() -> {
                Set<ExBlockHeader> results = new HashSet<>();
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
        if (searchIn.contains(ExSearchEntityType.TRANSACTION) && isHash32) {
            txsFuture = async(() -> {
                Set<ExTx> results = new HashSet<>();
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
        if (searchIn.contains(ExSearchEntityType.MEMPOOL) && isHash32) {
            mempoolFuture = async(() -> {
                Set<ExMemTransfer> results = new HashSet<>();
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
            tokensFuture = async(() -> {
                Set<ExToken> results = new HashSet<>();
                if (isAddress) {
                    try {
                        Address addr = Address.fromHexString(q);
                        tokenRepository.findById(new ExToken.ExTokenPK(addr)).ifPresent(results::add);
                    } catch (Exception e) {
                    }
                }
				if (isTextQuery && q.length() >= MIN_TEXT_QUERY_LENGTH) {
					results.addAll(tokenRepository.searchTokens(escapeLikePattern(q), MAX_TEXT_SEARCH_RESULTS));
				}
                return new ArrayList<>(results);
            });
        }

        // 5. Search Accounts & Aliases & Validators & Authorities
        if (searchIn.contains(ExSearchEntityType.ACCOUNT)) {
			if (isAddress) {
				accountsFuture = async(() -> {
					Set<ExAccountBalance> results = new HashSet<>();
					try {
						Address addr = Address.fromHexString(q);
						results.addAll(accountBalanceRepository.findByAddress(addr.toArray()));
					} catch (Exception e) {
					}
					return new ArrayList<>(results);
				});
			}

			if (isAddress || isTextQuery) {
				aliasesFuture = async(() -> {
					Set<ExAddressAlias> results = new HashSet<>();
					if (isAddress) {
						try {
							Address addr = Address.fromHexString(q);
							results.addAll(addressAliasRepository.findByAddress(addr));
						} catch (Exception e) {
						}
					}
					if (isTextQuery && q.length() >= MIN_TEXT_QUERY_LENGTH) {
						addressAliasRepository.findByAliasIgnoreCase(q).ifPresent(results::add);
					}
					return new ArrayList<>(results);
				});
			}
        }

        if (searchIn.contains(ExSearchEntityType.VALIDATOR) && isAddress) {
            validatorsFuture = async(() -> {
                Set<ExValidator> results = new HashSet<>();
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

        if (searchIn.contains(ExSearchEntityType.AUTHORITY) && isAddress) {
            authoritiesFuture = async(() -> {
                Set<ExAuthority> results = new HashSet<>();
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

		List<CompletableFuture<?>> futures = List.of(
				blocksFuture, txsFuture, mempoolFuture, tokensFuture,
				accountsFuture, aliasesFuture, validatorsFuture, authoritiesFuture);
		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
					.get(searchTimeoutMillis, TimeUnit.MILLISECONDS);
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
		} catch (InterruptedException e) {
			cancel(futures);
			Thread.currentThread().interrupt();
			throw new GEFailedException("Search interrupted", e);
		} catch (TimeoutException e) {
			cancel(futures);
			log.warn("Explorer search timed out after {} ms", searchTimeoutMillis);
			throw new GEFailedException("Search timed out", e);
		} catch (ExecutionException e) {
			cancel(futures);
            log.error("Error executing search", e);
            throw new GEFailedException("Search failed", e);
        }
    }

	private String validateAndNormalizeQuery(String query) {
		String normalized = query.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new GEValidationException("Search query must not be blank");
		}
		if (normalized.length() > MAX_QUERY_LENGTH) {
			throw new GEValidationException(
					String.format("Search query exceeds the maximum length (%d)", MAX_QUERY_LENGTH));
		}
		if (normalized.length() < MIN_TEXT_QUERY_LENGTH && !isNumber(normalized)) {
			throw new GEValidationException(
					String.format("Text search query must contain at least %d characters", MIN_TEXT_QUERY_LENGTH));
		}
		return normalized;
	}

	private String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
	}

	private <T> CompletableFuture<T> async(Supplier<T> supplier) {
		try {
			return CompletableFuture.supplyAsync(supplier, searchExecutor);
		} catch (RejectedExecutionException e) {
			throw new GEFailedException("Search capacity exhausted", e);
		}
	}

	private void cancel(List<CompletableFuture<?>> futures) {
		futures.forEach(future -> future.cancel(true));
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
