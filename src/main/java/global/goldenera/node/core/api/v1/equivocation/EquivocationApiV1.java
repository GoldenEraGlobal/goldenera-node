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
package global.goldenera.node.core.api.v1.equivocation;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.core.storage.blockchain.EquivocationEvidenceRepository;
import global.goldenera.node.shared.api.v1.equivocation.EquivocationEvidenceDtoV1;
import global.goldenera.node.shared.api.v1.equivocation.EquivocationEvidenceMapper;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.CoreApiSecurity;
import lombok.RequiredArgsConstructor;

@RestController("coreEquivocationApiV1")
@RequiredArgsConstructor
@RequestMapping("/api/core/v1/equivocation")
public class EquivocationApiV1 {

	private final EquivocationEvidenceRepository repository;
	private final EquivocationEvidenceMapper mapper;

	@CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
	@GetMapping("list")
	public ResponseEntity<List<EquivocationEvidenceDtoV1>> list(
			@RequestParam(defaultValue = "100") int limit) {
		return ResponseEntity.ok(repository.findConflicts(limit).stream().map(mapper::map).toList());
	}
}
