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
package global.goldenera.node.core.sandbox.control;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AuditPage;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AutonomousRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AutonomousState;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Capabilities;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Candidate;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateBatch;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateBatchRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.ExactOneRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Operation;
import global.goldenera.node.core.sandbox.control.SandboxControlService.Submission;

@RestController
@Profile("sandbox")
@ConditionalOnProperty(
		prefix = "ge.sandbox.control-api",
		name = "enabled",
		havingValue = "true")
@RequestMapping("/api/sandbox/v1/control")
public class SandboxControlController {

	private final SandboxControlService service;

	SandboxControlController(SandboxControlService service, SandboxControlActivation activation) {
		this.service = service;
	}

	@GetMapping("/capabilities")
	Capabilities capabilities() {
		return service.capabilities();
	}

	@GetMapping("/state")
	AutonomousState state() {
		return service.state();
	}

	@PutMapping(path = "/autonomous", consumes = "application/json")
	AutonomousState setAutonomous(@RequestBody AutonomousRequest request) {
		return service.setAutonomous(request);
	}

	@PostMapping(path = "/exact-one", consumes = "application/json")
	ResponseEntity<Operation> exactOne(
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ExactOneRequest request) {
		Submission submission = service.submitExactOne(idempotencyKey, request);
		URI location = URI.create("/api/sandbox/v1/control/requests/" + submission.operation().operationId());
		return ResponseEntity.accepted().location(location).body(submission.operation());
	}

	@PostMapping(path = "/author-candidate", consumes = "application/json")
	Candidate authorCandidate(@RequestBody CandidateRequest request) {
		return service.authorCandidate(request);
	}

	@PostMapping(path = "/author-candidates", consumes = "application/json")
	CandidateBatch authorCandidates(@RequestBody CandidateBatchRequest request) {
		return service.authorCandidates(request);
	}

	@GetMapping("/requests/{operationId}")
	Operation operation(@PathVariable String operationId) {
		return service.operation(operationId);
	}

	@GetMapping("/audit")
	AuditPage audit(
			@RequestParam(required = false) Long after,
			@RequestParam(defaultValue = "100") int limit) {
		return service.audit(after, limit);
	}
}
