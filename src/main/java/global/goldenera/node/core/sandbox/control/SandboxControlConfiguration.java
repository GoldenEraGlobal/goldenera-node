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

import java.time.Duration;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadiness;
import global.goldenera.node.core.p2p.manager.NodeConnectionManager;
import global.goldenera.node.core.p2p.services.P2PHeadAnnouncementService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import io.github.bucket4j.Bucket;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
@Profile("sandbox")
@ConditionalOnProperty(
		prefix = "ge.sandbox.control-api",
		name = "enabled",
		havingValue = "true")
@EnableConfigurationProperties(SandboxControlProperties.class)
public class SandboxControlConfiguration {

	static final String ROUTE_PATTERN = "/api/sandbox/v1/control/**";
	static final long RATE_CAPACITY = 20;
	static final long RATE_REFILL_PER_SECOND = 10;
	static final String OPENAPI_BEARER_SCHEME = "SandboxControlBearer";

	@Bean
	SandboxControlActivation sandboxControlActivation(
			SandboxRuntimeContext runtimeContext,
			SandboxControlProperties properties) {
		return new SandboxControlActivationValidator().validate(runtimeContext, properties);
	}

	@Bean
	SandboxControlTokenAuthenticator sandboxControlTokenAuthenticator(SandboxControlProperties properties) {
		return SandboxControlTokenAuthenticator.load(properties.getTokenFile());
	}

	@Bean
	SandboxControlAuditLog sandboxControlAuditLog() {
		return new SandboxControlAuditLog();
	}

	@Bean
	SandboxControlOperationRegistry sandboxControlOperationRegistry(SandboxControlAuditLog auditLog) {
		return new SandboxControlOperationRegistry(auditLog);
	}

	@Bean
	@ConditionalOnMissingBean(CoreRuntimeReadiness.class)
	CoreRuntimeReadiness sandboxControlFailClosedCoreRuntimeReadiness() {
		return () -> false;
	}

	@Bean
	SandboxControlService sandboxControlService(
			MiningService miningService,
			SandboxControlActivation activation,
			SandboxControlOperationRegistry operations,
			SandboxControlAuditLog auditLog,
			ProofOfWorkProvider proofOfWorkProvider,
			ChainClock chainClock,
			MiningProperties miningProperties,
			AuthoritativeChainIdentityProvider identityProvider,
			CoreRuntimeReadiness coreReadiness,
			NodeConnectionManager connectionManager,
			P2PHeadAnnouncementService headAnnouncementService,
			BlockSyncManagerService syncManager,
			MempoolManager mempoolManager) {
		return new SandboxControlService(
				miningService,
				activation,
				operations,
				auditLog,
				proofOfWorkProvider,
				chainClock,
				miningProperties,
				identityProvider,
				coreReadiness,
				connectionManager,
				headAnnouncementService,
				syncManager,
				mempoolManager);
	}

	@Bean
	GroupedOpenApi sandboxControlOpenApi() {
		return GroupedOpenApi.builder()
				.group("Sandbox Control API")
				.pathsToMatch(ROUTE_PATTERN)
				.addOpenApiCustomizer(openApi -> {
					if (openApi.getComponents() == null) {
						openApi.setComponents(new Components());
					}
					openApi.getComponents().addSecuritySchemes(
							OPENAPI_BEARER_SCHEME,
							new SecurityScheme()
									.type(SecurityScheme.Type.HTTP)
									.scheme("bearer")
									.bearerFormat("base64url-32-byte"));
				})
				.addOperationCustomizer((operation, handlerMethod) -> {
					operation.addSecurityItem(new SecurityRequirement().addList(OPENAPI_BEARER_SCHEME));
					return operation;
				})
				.build();
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain sandboxControlSecurityFilterChain(
			HttpSecurity http,
			SandboxControlTokenAuthenticator authenticator,
			SandboxControlAuditLog auditLog,
			ObjectMapper objectMapper) throws Exception {
		Bucket authenticatedRateBucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(RATE_CAPACITY)
						.refillGreedy(RATE_REFILL_PER_SECOND, Duration.ofSeconds(1)))
				.build();
		Bucket authenticationFailureRateBucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(RATE_CAPACITY)
						.refillGreedy(RATE_REFILL_PER_SECOND, Duration.ofSeconds(1)))
				.build();
		SandboxControlSecurityFilter filter =
				new SandboxControlSecurityFilter(
						authenticator,
						auditLog,
						authenticatedRateBucket,
						authenticationFailureRateBucket,
						objectMapper);
		http.securityMatcher(ROUTE_PATTERN)
				.cors(cors -> cors.disable())
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(login -> login.disable())
				.logout(logout -> logout.disable())
				.requestCache(cache -> cache.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SANDBOX_CONTROL"))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, failure) -> {
					response.setStatus(401);
					response.setContentType("application/json");
					response.setHeader("Cache-Control", "no-store");
					objectMapper.writeValue(response.getOutputStream(),
							new SandboxControlDtos.Error("UNAUTHORIZED", "Bearer authentication is required"));
				}))
				.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
