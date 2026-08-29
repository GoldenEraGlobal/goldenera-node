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
package global.goldenera.node.bridge.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import global.goldenera.node.bridge.services.BridgeAddressService;
import global.goldenera.node.bridge.services.BridgeBlockService;
import global.goldenera.node.bridge.services.BridgeTxService;

class BridgeControllerCapabilityContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BridgeAddressService.class, () -> mock(BridgeAddressService.class))
            .withBean(BridgeBlockService.class, () -> mock(BridgeBlockService.class))
            .withBean(BridgeTxService.class, () -> mock(BridgeTxService.class))
            .withUserConfiguration(
                    BridgeAddressApiV1.class,
                    BridgeBlockApiV1.class,
                    BridgeTxApiV1.class,
                    BridgeSubscriptionApiV1.class);

    @Test
    void coreBridgeControllersRemainAvailableWithoutPostgresql() {
        contextRunner
                .withPropertyValues(
                        "ge.general.postgresql-enable=false",
                        "ge.general.webhook-enable=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(BridgeAddressApiV1.class);
                    assertThat(context).hasSingleBean(BridgeBlockApiV1.class);
                    assertThat(context).hasSingleBean(BridgeTxApiV1.class);
                    assertThat(context).doesNotHaveBean(BridgeSubscriptionApiV1.class);
                });
    }

    @Test
    void subscriptionControllerRequiresBothPostgresqlAndWebhooks() {
        contextRunner
                .withPropertyValues(
                        "ge.general.postgresql-enable=true",
                        "ge.general.webhook-enable=false")
                .run(context -> assertThat(context).doesNotHaveBean(BridgeSubscriptionApiV1.class));
    }

    @Test
    void subscriptionControllerIsAvailableWithPostgresqlAndWebhooks() {
        contextRunner
                .withPropertyValues(
                        "ge.general.postgresql-enable=true",
                        "ge.general.webhook-enable=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BridgeAddressApiV1.class);
                    assertThat(context).hasSingleBean(BridgeBlockApiV1.class);
                    assertThat(context).hasSingleBean(BridgeTxApiV1.class);
                    assertThat(context).hasSingleBean(BridgeSubscriptionApiV1.class);
                });
    }
}
