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
package global.goldenera.node.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class MempoolPropertiesValidationTest {

	@Test
	void maximumRecommendedFeeMustBeAPositiveUint256() {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Validator validator = factory.getValidator();
			MempoolProperties properties = new MempoolProperties();

			properties.setMaxRecommendedFeeWei(BigInteger.ZERO);
			assertThat(validator.validate(properties))
					.anyMatch(violation -> violation.getPropertyPath().toString().equals("maxRecommendedFeeWei"));

			properties.setMaxRecommendedFeeWei(BigInteger.ONE.shiftLeft(256));
			assertThat(validator.validate(properties))
					.anyMatch(violation -> violation.getPropertyPath().toString().equals("maxRecommendedFeeWei"));

			properties.setMaxRecommendedFeeWei(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
			assertThat(validator.validate(properties)).isEmpty();
		}
	}
}
