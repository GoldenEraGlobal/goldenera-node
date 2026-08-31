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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Error;

@RestControllerAdvice(assignableTypes = SandboxControlController.class)
@Profile("sandbox")
@ConditionalOnProperty(
		prefix = "ge.sandbox.control-api",
		name = "enabled",
		havingValue = "true")
public class SandboxControlExceptionHandler {

	private final SandboxControlAuditLog auditLog;

	SandboxControlExceptionHandler(SandboxControlAuditLog auditLog, SandboxControlActivation activation) {
		this.auditLog = auditLog;
	}

	@ExceptionHandler(SandboxControlException.class)
	ResponseEntity<Error> controlError(SandboxControlException exception) {
		auditLog.record(SandboxControlAuditLog.Action.REQUEST_REJECTED,
				exception.code(), exception.operationId());
		return ResponseEntity.status(exception.status())
				.body(new Error(exception.code(), exception.getMessage(), exception.operationId()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Error> invalidJson() {
		auditLog.record(SandboxControlAuditLog.Action.REQUEST_REJECTED, "INVALID_JSON", null);
		return ResponseEntity.badRequest().body(new Error("INVALID_JSON", "Request body must be strict JSON"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Error> unsupportedMediaType() {
		auditLog.record(SandboxControlAuditLog.Action.REQUEST_REJECTED, "UNSUPPORTED_MEDIA_TYPE", null);
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(new Error("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Error> invalidArgument() {
		auditLog.record(SandboxControlAuditLog.Action.REQUEST_REJECTED, "INVALID_ARGUMENT", null);
		return ResponseEntity.badRequest().body(new Error("INVALID_ARGUMENT", "Request argument is invalid"));
	}

	@ExceptionHandler(RuntimeException.class)
	ResponseEntity<Error> internalFailure() {
		auditLog.record(SandboxControlAuditLog.Action.REQUEST_REJECTED, "INTERNAL_ERROR", null);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new Error("INTERNAL_ERROR", "Sandbox control request failed"));
	}
}
