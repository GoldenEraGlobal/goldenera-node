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
package global.goldenera.node.explorer.storage.chainidentity;

import java.sql.SQLException;

import org.springframework.beans.factory.InitializingBean;

import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotBootstrapService;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotException;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildTrigger;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.extern.slf4j.Slf4j;

/** Explorer-only readiness boundary. Failures isolate explorer from core. */
@Slf4j
public final class ExplorerChainIdentityInitializer implements InitializingBean {

	public static final String BEAN_NAME = "explorerChainIdentityInitializer";

	private final AuthoritativeChainIdentityProvider authoritativeIdentityProvider;
	private final ExplorerSchemaMigrator schemaMigrator;
	private final ExplorerChainIdentityGuard guard;
	private final ExplorerRuntimeReadiness readiness;
	private final GeneralProperties generalProperties;
	private final ExplorerSnapshotBootstrapService snapshotBootstrap;
	private final ExplorerArchiveRebuildTrigger archiveRebuildTrigger;

	public ExplorerChainIdentityInitializer(
			AuthoritativeChainIdentityProvider authoritativeIdentityProvider,
			ExplorerSchemaMigrator schemaMigrator,
			ExplorerChainIdentityGuard guard,
			ExplorerRuntimeReadiness readiness) {
		this(authoritativeIdentityProvider, schemaMigrator, guard, readiness, null, null, null);
	}

	public ExplorerChainIdentityInitializer(
			AuthoritativeChainIdentityProvider authoritativeIdentityProvider,
			ExplorerSchemaMigrator schemaMigrator,
			ExplorerChainIdentityGuard guard,
			ExplorerRuntimeReadiness readiness,
			GeneralProperties generalProperties,
			ExplorerSnapshotBootstrapService snapshotBootstrap) {
		this(authoritativeIdentityProvider, schemaMigrator, guard, readiness,
				generalProperties, snapshotBootstrap, null);
	}

	public ExplorerChainIdentityInitializer(
			AuthoritativeChainIdentityProvider authoritativeIdentityProvider,
			ExplorerSchemaMigrator schemaMigrator,
			ExplorerChainIdentityGuard guard,
			ExplorerRuntimeReadiness readiness,
			GeneralProperties generalProperties,
			ExplorerSnapshotBootstrapService snapshotBootstrap,
			ExplorerArchiveRebuildTrigger archiveRebuildTrigger) {
		this.authoritativeIdentityProvider = authoritativeIdentityProvider;
		this.schemaMigrator = schemaMigrator;
		this.guard = guard;
		this.readiness = readiness;
		this.generalProperties = generalProperties;
		this.snapshotBootstrap = snapshotBootstrap;
		this.archiveRebuildTrigger = archiveRebuildTrigger;
	}

	@Override
	public void afterPropertiesSet() {
		if (generalProperties != null && !generalProperties.isExplorerEnable()) {
			return;
		}
		try {
			schemaMigrator.migrate();
			StoredChainIdentity identity = authoritativeIdentityProvider.identity();
			guard.verifyAndBind(identity);
			if (snapshotBootstrap != null) {
				snapshotBootstrap.prepareForIndexing(identity);
			}
			readiness.ready();
		} catch (ExplorerSnapshotException e) {
			if (archiveRebuildTrigger != null) {
				archiveRebuildTrigger.request();
			} else {
				readiness.failed(ExplorerReadinessState.SNAPSHOT_UNAVAILABLE, e.getMessage());
			}
			log.error("Explorer snapshot bootstrap failed; core remains available and local rebuild starts: {}",
					e.getMessage());
		} catch (ExplorerChainIdentityException e) {
			readiness.failed(e.state(), e.getMessage());
			log.error("Explorer disabled by its storage safety boundary: {}", e.getMessage());
		} catch (RuntimeException e) {
			ExplorerReadinessState state = containsSqlFailure(e)
					? ExplorerReadinessState.DATABASE_UNAVAILABLE
					: ExplorerReadinessState.STORAGE_CORRUPT;
			String detail = state == ExplorerReadinessState.DATABASE_UNAVAILABLE
					? "Explorer PostgreSQL is unavailable"
					: rootMessage(e);
			readiness.failed(state, detail);
			log.error("Explorer database initialization failed; core remains available", e);
		}
	}

	private boolean containsSqlFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof SQLException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() == null ? failure.getClass().getSimpleName() : current.getMessage();
	}
}
