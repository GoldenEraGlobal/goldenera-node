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
package global.goldenera.node.admin.api.v1.apikey;

import static lombok.AccessLevel.PRIVATE;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.admin.api.v1.apikey.dtos.ApiKeyDtoV1;
import global.goldenera.node.admin.api.v1.apikey.dtos.ApiKeyDtoV1_Page;
import global.goldenera.node.admin.api.v1.apikey.dtos.ApiKeyInDtoV1;
import global.goldenera.node.admin.api.v1.apikey.dtos.ApiKeySetEnabledInDtoV1;
import global.goldenera.node.admin.api.v1.apikey.mappers.ApiKeyMapper;
import global.goldenera.node.admin.services.ApiKeyService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.services.ApiKeyCoreService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(value = "api/admin/v1/api-key")
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ApiKeyApiV1 {

	ApiKeyCoreService apiKeyCoreService;
	ApiKeyService apiKeyService;
	ApiKeyMapper apiKeyMapper;

	@GetMapping("page")
	public ApiKeyDtoV1_Page apiV1ApiKeyGetPage(
			@RequestParam int pageNumber,
			@RequestParam int pageSize,
			@RequestParam(required = false) Sort.Direction direction,
			@RequestParam(required = false) String labelLike,
			@RequestParam(required = false) Boolean enabled,
			@RequestParam(required = false) Boolean expired) {
		return apiKeyMapper
				.map(apiKeyCoreService.getPage(pageNumber, pageSize, direction, labelLike, enabled, expired));
	}

	@GetMapping("{id}")
	public ApiKeyDtoV1 apiV1ApiKeyGetById(
			@PathVariable Long id) {
		return apiKeyMapper.map(apiKeyCoreService.getById(id));
	}

	@PostMapping
	public ApiKeyDtoV1.CreatedApiKeyDtoV1 apiV1CreateApiKeyCreate(
			@RequestBody ApiKeyInDtoV1 payload) {
		ApiKeyService.CreatedApiKey createdApiKey = apiKeyService
				.createApiKey(
						payload.getLabel(),
						payload.getDescription(),
						payload.getPermissions(),
						payload.getExpiresAt(),
						payload.getMaxWebhooks());
		return apiKeyMapper.map(createdApiKey);
	}

	@PutMapping("{id}")
	public ApiKeyDtoV1 apiV1CreateApiKeyUpdate(
			@PathVariable Long id,
			@RequestBody ApiKeyInDtoV1 payload) {
		ApiKey apiKey = apiKeyService
				.updateApiKey(
						id,
						payload.getLabel(),
						payload.getDescription(),
						payload.getPermissions(),
						payload.getExpiresAt(),
						payload.getMaxWebhooks());
		return apiKeyMapper.map(apiKey);
	}

	@PutMapping("{id}/set-enabled")
	public ApiKeyDtoV1 apiV1CreateApiKeySetEnabled(
			@PathVariable Long id,
			@RequestBody ApiKeySetEnabledInDtoV1 payload) {
		ApiKey apiKey = apiKeyService.toggleApiKeyEnabled(id, payload.isEnabled());
		return apiKeyMapper.map(apiKey);
	}

}
