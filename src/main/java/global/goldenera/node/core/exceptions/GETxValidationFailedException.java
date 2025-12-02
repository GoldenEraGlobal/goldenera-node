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
package global.goldenera.node.core.exceptions;

import static lombok.AccessLevel.PRIVATE;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class GETxValidationFailedException extends GEValidationException {

	Tx failedTx;

	public GETxValidationFailedException(Tx failedTx) {
		super(String.format("Transaction %s failed validation: %s",
				failedTx.getHash().toShortLogString()));
		this.failedTx = failedTx;
	}

	public GETxValidationFailedException(Tx failedTx, String message) {
		super(String.format("Transaction %s failed validation: %s",
				failedTx.getHash().toShortLogString(), message));
		this.failedTx = failedTx;
	}

	public GETxValidationFailedException(Tx failedTx, Throwable cause) {
		super(String.format("Transaction %s failed validation: %s",
				failedTx.getHash().toShortLogString()), cause);
		this.failedTx = failedTx;
	}

	public GETxValidationFailedException(Tx failedTx, String message, Throwable cause) {
		super(String.format("Transaction %s failed validation: %s",
				failedTx.getHash().toShortLogString(), message), cause);
		this.failedTx = failedTx;
	}
}
