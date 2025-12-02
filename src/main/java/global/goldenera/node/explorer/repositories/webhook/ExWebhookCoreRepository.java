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
package global.goldenera.node.explorer.repositories.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.node.explorer.entities.webhook.ExWebhook;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface ExWebhookCoreRepository extends BaseJpaRepository<ExWebhook, UUID>,
		ListPagingAndSortingRepository<ExWebhook, UUID>, JpaSpecificationExecutor<ExWebhook> {

	@Query("SELECT w FROM ExWebhook w WHERE w.createdByApiKey.id = :apiKeyId")
	List<ExWebhook> findByApiKeyId(@Param("apiKeyId") long apiKeyId);

	@Query("SELECT COUNT(w) FROM ExWebhook w WHERE w.createdByApiKey.id = :apiKeyId")
	long countByApiKeyId(@Param("apiKeyId") long apiKeyId);

	@Query("SELECT w FROM ExWebhook w LEFT JOIN FETCH w.events JOIN FETCH w.createdByApiKey k WHERE w.enabled = true AND k.enabled = true")
	List<ExWebhook> findAllEnabledWithEvents();

	@Query("SELECT w FROM ExWebhook w LEFT JOIN FETCH w.events JOIN FETCH w.createdByApiKey k WHERE w.createdByApiKey.id = :apiKeyId AND w.enabled = true AND k.enabled = true")
	List<ExWebhook> findEnabledByApiKeyIdWithEvents(@Param("apiKeyId") long apiKeyId);

	@Query("SELECT w FROM ExWebhook w LEFT JOIN FETCH w.events WHERE w.id = :id")
	Optional<ExWebhook> findByIdWithEvents(@Param("id") UUID id);
}
