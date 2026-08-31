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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.config.CoreOnlySecurityConfiguration;
import global.goldenera.node.core.blockchain.pow.DeterministicSha256ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.time.ProductionChainClock;
import global.goldenera.node.core.mining.AutonomousMiningState;
import global.goldenera.node.core.mining.ExactOneMiningOutcome;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.mining.MiningSuspensionReason;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadiness;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadinessTracker;
import global.goldenera.node.core.p2p.manager.NodeConnectionManager;
import global.goldenera.node.core.p2p.services.P2PHeadAnnouncementService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.BlockIngestionOutcome;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.config.SecurityConfig;
import global.goldenera.node.shared.filters.ThrottlingFilter;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.properties.SecurityProperties;
import global.goldenera.node.shared.services.ThrottlingService;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.Cookie;

class SandboxControlWebTest {

	private static final String TOKEN = token((byte) 4);
	private static final String AUTHORIZATION = "Bearer " + TOKEN;

	@TempDir
	Path temporaryDirectory;

	@Test
	void enabledControlChainPrecedesExplorerAndCoreOnlyGraphsAndRegistersHandlers() throws Exception {
		for (boolean explorerEnabled : new boolean[]{true, false}) {
			try (Fixture fixture = fixture(true, explorerEnabled, true)) {
				assertThat(fixture.context.getBeansOfType(SandboxControlController.class)).hasSize(1);
				GroupedOpenApi apiGroup = fixture.context.getBean(GroupedOpenApi.class);
				assertThat(apiGroup.getPathsToMatch()).containsExactly(SandboxControlConfiguration.ROUTE_PATTERN);
				OpenAPI openApi = new OpenAPI();
				apiGroup.getOpenApiCustomizers().forEach(customizer -> customizer.customise(openApi));
				assertThat(openApi.getComponents().getSecuritySchemes()
						.get(SandboxControlConfiguration.OPENAPI_BEARER_SCHEME).getType())
						.isEqualTo(SecurityScheme.Type.HTTP);
				Operation operation = new Operation();
				apiGroup.getOperationCustomizers().forEach(customizer -> customizer.customize(operation, null));
				assertThat(operation.getSecurity().getFirst())
						.containsKey(SandboxControlConfiguration.OPENAPI_BEARER_SCHEME);
				assertThat(fixture.context.getBean(FilterChainProxy.class).getFilterChains()).isNotEmpty();
				assertThat(fixture.context.getBean(FilterChainProxy.class).getFilterChains().getFirst()
						.matches(new MockHttpServletRequest("GET", "/api/sandbox/v1/control/state"))).isTrue();
				assertThat(controlHandlerPaths(fixture.context))
						.contains("/api/sandbox/v1/control/capabilities", "/api/sandbox/v1/control/exact-one");
				fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
						.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
						.andExpect(status().isOk())
						.andExpect(jsonPath("$.proofOfWork").value("DETERMINISTIC_SHA256_V1"))
						.andExpect(jsonPath("$.clock").value("PRODUCTION"))
						.andExpect(jsonPath("$.chainIdentitySource").value("AUTHORITATIVE_ROCKSDB"))
						.andExpect(jsonPath("$.coreReadiness").value("READY"))
						.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
			}
		}
	}

	@Test
	void disabledControlPlaneHasNoBeansHandlersOrRoute() throws Exception {
		for (boolean explorerEnabled : new boolean[]{true, false}) {
			try (Fixture fixture = fixture(false, explorerEnabled, true)) {
				assertThat(fixture.context.getBeansOfType(SandboxControlController.class)).isEmpty();
				assertThat(fixture.context.getBeansOfType(GroupedOpenApi.class)).isEmpty();
				assertThat(controlHandlerPaths(fixture.context)).isEmpty();
				fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities"))
						.andExpect(status().isNotFound());
			}
		}
	}

	@Test
	void productionProfileHasNoControlBeansHandlersOrRouteEvenWhenEnablePropertyIsSet() throws Exception {
		AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		context.getEnvironment().setActiveProfiles("prod");
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
				"sandboxControlProductionProfileTest",
				Map.of("ge.sandbox.control-api.enabled", "true")));
		context.register(
				SandboxControlConfiguration.class,
				SandboxControlController.class,
				SandboxControlExceptionHandler.class,
				WebConfiguration.class);
		try {
			context.refresh();
			assertThat(context.getBeansOfType(SandboxControlController.class)).isEmpty();
			assertThat(context.getBeansOfType(GroupedOpenApi.class)).isEmpty();
			assertThat(controlHandlerPaths(context)).isEmpty();
			MockMvcBuilders.webAppContextSetup(context).build()
					.perform(get("/api/sandbox/v1/control/capabilities"))
					.andExpect(status().isNotFound());
		} finally {
			context.close();
		}
	}

	@Test
	void enabledPropertyFailsClosedWhenManifestCapabilityIsMissing() throws Exception {
		assertThat(catchThrowable(() -> fixture(true, false, false)))
				.isNotNull();
	}

	@Test
	void mutationsFailClosedUntilTheExplicitCoreLifecycleReachesReady() throws Exception {
		try (Fixture fixture = fixture(true, false, true, false)) {
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "not-ready")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"deadlineMs\":30000}"))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("CORE_NOT_READY"));

			assertThat(fixture.context.getBeansOfType(CoreRuntimeReadiness.class)).hasSize(1);
			assertThat(fixture.context.getBean(CoreRuntimeReadiness.class))
					.isInstanceOf(CoreRuntimeReadinessTracker.class);
			advanceCoreToReady(fixture.context);

			fixture.mockMvc.perform(put("/api/sandbox/v1/control/autonomous")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"enabled\":false}"))
					.andExpect(status().isOk());
		}
	}

	@Test
	void authenticatedSandboxCanRequestImmediateP2pMaintenance() throws Exception {
		try (Fixture fixture = fixture(true, false, true)) {
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/p2p/maintenance")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.queued").value(false))
					.andExpect(jsonPath("$.connectedPeers").value(0));
			verify(fixture.context.getBean(P2PHeadAnnouncementService.class)).requestAnnouncement();
		}
	}

	@Test
	void authenticatedSandboxCanExplicitlyClearTheMempool() throws Exception {
		try (Fixture fixture = fixture(true, false, true)) {
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/mempool/clear")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clearedTransactions").value(0));
		}
	}

	@Test
	void acceptsOnlyOneCorrectBearerSourceAndNeverReturnsSecrets() throws Exception {
		try (Fixture fixture = fixture(true, false, true)) {
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token((byte) 5)))
					.andExpect(status().isUnauthorized());
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isUnauthorized());
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities?access_token=" + TOKEN)
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isUnauthorized());
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("X-API-Key", TOKEN))
					.andExpect(status().isUnauthorized());
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.cookie(new Cookie("token", TOKEN)))
					.andExpect(status().isUnauthorized());

			String response = fixture.mockMvc.perform(get("/api/sandbox/v1/control/capabilities")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertThat(response).doesNotContain(TOKEN, fixture.tokenPath.toString());
		}
	}

	@Test
	void authenticationFailureRateBucketCannotExhaustAuthenticatedTraffic() throws Exception {
		try (Fixture fixture = fixture(true, false, true)) {
			for (int index = 0; index < SandboxControlConfiguration.RATE_CAPACITY; index++) {
				fixture.mockMvc.perform(get("/api/sandbox/v1/control/state")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token((byte) 99)))
						.andExpect(status().isUnauthorized());
			}
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/state")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token((byte) 99)))
					.andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/state")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isOk());
		}
	}

	@Test
	void enforcesBodyRateConcurrencyAndIdempotencyWithoutWaiting() throws Exception {
		try (Fixture fixture = fixture(true, false, true)) {
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "oversized")
					.contentType(MediaType.APPLICATION_JSON)
					.content("x".repeat(SandboxControlSecurityFilter.MAX_REQUEST_BODY_BYTES + 1)))
					.andExpect(status().isPayloadTooLarge())
					.andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"));

			String body = "{\"scheduledTimestamp\":\"2027-01-01T00:00:00Z\",\"deadlineMs\":30000}";
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "unknown-field")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"deadlineMs\":30000,\"unknown\":true}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_JSON"));
			for (String invalid : new String[]{
					"{\"deadlineMs\":1.5}",
					"{\"deadlineMs\":9223372036854775808}",
					"{\"deadlineMs\":\"30000\"}",
					"{\"deadlineMs\":1,\"deadlineMs\":2}",
					"{\"deadlineMs\":30000}{\"deadlineMs\":30000}"}) {
				fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
						.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
						.header("Idempotency-Key", "invalid-json")
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalid))
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("INVALID_JSON"));
			}
			for (String invalid : new String[]{
					"{\"enabled\":\"true\"}",
					"{\"enabled\":1}",
					"{\"enabled\":null}"}) {
				fixture.mockMvc.perform(put("/api/sandbox/v1/control/autonomous")
						.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalid))
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("INVALID_JSON"));
			}
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "wrong-content-type")
					.contentType(MediaType.TEXT_PLAIN)
					.content(body))
					.andExpect(status().isUnsupportedMediaType())
					.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
			MvcResult first = fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "operation-a")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.status").value("PENDING"))
					.andReturn();
			String operationId = new ObjectMapper().readTree(
					first.getResponse().getContentAsByteArray()).required("operationId").textValue();

			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "operation-a")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.operationId").value(operationId));
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "operation-a")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"deadlineMs\":1}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "operation-b")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("MUTATION_BUSY"));

			fixture.miningResult.complete(new ExactOneMiningOutcome(
					ExactOneMiningOutcome.Code.ACCEPTED,
					null,
					18L,
					null,
					BlockIngestionOutcome.Code.ACCEPTED));
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/requests/{id}", operationId)
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("COMPLETED"))
					.andExpect(jsonPath("$.outcome.code").value("ACCEPTED"))
					.andExpect(jsonPath("$.outcome.ingestionCode").value("ACCEPTED"));
			fixture.mockMvc.perform(post("/api/sandbox/v1/control/exact-one")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
					.header("Idempotency-Key", "operation-a")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isAccepted())
					.andExpect(header().string(HttpHeaders.LOCATION,
							"/api/sandbox/v1/control/requests/" + operationId));
			String audit = fixture.mockMvc.perform(get("/api/sandbox/v1/control/audit?limit=100")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertThat(audit).doesNotContain(TOKEN, "operation-a", fixture.tokenPath.toString());
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/audit?limit=101")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_AUDIT_LIMIT"));
		}

		try (Fixture fixture = fixture(true, false, true)) {
			for (int index = 0; index < SandboxControlConfiguration.RATE_CAPACITY; index++) {
				fixture.mockMvc.perform(get("/api/sandbox/v1/control/state")
						.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
						.andExpect(status().isOk());
			}
			fixture.mockMvc.perform(get("/api/sandbox/v1/control/state")
					.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
					.andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		}
	}

	@SuppressWarnings("unchecked")
	private Fixture fixture(boolean controlEnabled, boolean explorerEnabled, boolean manifestCapability)
			throws Exception {
		return fixture(controlEnabled, explorerEnabled, manifestCapability, true);
	}

	@SuppressWarnings("unchecked")
	private Fixture fixture(
			boolean controlEnabled,
			boolean explorerEnabled,
			boolean manifestCapability,
			boolean coreReady) throws Exception {
		Path tokenPath = temporaryDirectory.resolve("token-" + System.nanoTime());
		Files.writeString(tokenPath, TOKEN, StandardCharsets.US_ASCII);
		Files.setPosixFilePermissions(tokenPath,
				EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

		AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		context.getEnvironment().setActiveProfiles("sandbox");
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
				"sandboxControlWebTest",
				Map.of(
						"ge.sandbox.control-api.enabled", Boolean.toString(controlEnabled),
						"ge.sandbox.control-api.token-file", tokenPath.toString(),
						"ge.general.explorer-enable", Boolean.toString(explorerEnabled),
						"ge.general.postgresql-enable", Boolean.toString(explorerEnabled))));
		SandboxRuntimeContext runtimeContext = runtime(manifestCapability);
		context.addBeanFactoryPostProcessor(beanFactory ->
				beanFactory.registerSingleton("sandboxRuntimeContext", runtimeContext));
		context.register(
				CoreRuntimeReadinessTracker.class,
				SandboxControlConfiguration.class,
				SandboxControlController.class,
				SandboxControlExceptionHandler.class,
				WebConfiguration.class,
				explorerEnabled ? ExplorerSecurityBeans.class : CoreOnlySecurityBeans.class,
				explorerEnabled ? SecurityConfig.class : CoreOnlySecurityConfiguration.class);
		try {
			context.refresh();
		} catch (RuntimeException e) {
			context.close();
			throw e;
		}
		if (coreReady) {
			advanceCoreToReady(context);
		}
		MiningService miningService = context.getBean(MiningService.class);
		CompletableFuture<ExactOneMiningOutcome> miningResult = context.getBean("miningResult", CompletableFuture.class);
		when(miningService.mineExactlyOne(any())).thenReturn(miningResult);
		when(miningService.getAutonomousMiningState()).thenReturn(
				new AutonomousMiningState(true, true, false, false, false, EnumSet.noneOf(MiningSuspensionReason.class)));
		when(miningService.pauseAutonomousMining(SandboxControlService.AUTONOMOUS_PAUSE_TIMEOUT)).thenReturn(true);
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(context.getBean(FilterChainProxy.class))
				.build();
		return new Fixture(context, mockMvc, tokenPath, miningResult);
	}

	private void advanceCoreToReady(AnnotationConfigWebApplicationContext context) {
		CoreRuntimeReadinessTracker readiness = context.getBean(CoreRuntimeReadinessTracker.class);
		readiness.chainIdentityBound();
		readiness.genesisHeadReady();
		readiness.p2pListenerBound();
		readiness.coreReady();
	}

	private SandboxRuntimeContext runtime(boolean controlApi) throws Exception {
		String json;
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		Path manifest = temporaryDirectory.resolve("web-manifest-" + controlApi + "-" + System.nanoTime() + ".json");
		Files.writeString(manifest, json.replace("\"controlApi\": false", "\"controlApi\": " + controlApi),
				StandardCharsets.UTF_8);
		SandboxManifestContext manifestContext = new SandboxManifestLoader().load(manifest);
		return new SandboxRuntimeContext(ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifestContext));
	}

	private Set<String> controlHandlerPaths(AnnotationConfigWebApplicationContext context) {
		return context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPatternValues().stream())
				.filter(path -> path.startsWith("/api/sandbox/v1/control"))
				.collect(Collectors.toSet());
	}

	private static String token(byte value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class WebConfiguration {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		MiningService miningService() {
			return mock(MiningService.class);
		}

		@Bean
		NodeConnectionManager nodeConnectionManager() {
			return mock(NodeConnectionManager.class);
		}

		@Bean
		P2PHeadAnnouncementService p2pHeadAnnouncementService() {
			return mock(P2PHeadAnnouncementService.class);
		}

		@Bean
		BlockSyncManagerService blockSyncManagerService() {
			return mock(BlockSyncManagerService.class);
		}

		@Bean
		MempoolManager mempoolManager() {
			return mock(MempoolManager.class);
		}

		@Bean
		ProofOfWorkProvider proofOfWorkProvider() {
			return mock(DeterministicSha256ProofOfWorkProvider.class);
		}

		@Bean
		ChainClock chainClock() {
			return new ProductionChainClock();
		}

		@Bean
		MiningProperties miningProperties() {
			return new MiningProperties();
		}

		@Bean
		AuthoritativeChainIdentityProvider authoritativeChainIdentityProvider() {
			AuthoritativeChainIdentityProvider provider = mock(AuthoritativeChainIdentityProvider.class);
			when(provider.identity()).thenReturn(new StoredChainIdentity(
					StoredChainIdentity.CURRENT_FORMAT_VERSION,
					Network.TESTNET.getCode(),
					"sandbox-web-test",
					"0x" + "1".repeat(64),
					"2".repeat(64)));
			return provider;
		}

		@Bean
		CompletableFuture<ExactOneMiningOutcome> miningResult() {
			return new CompletableFuture<>();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class ExplorerSecurityBeans {

		@Bean
		GeneralProperties generalProperties() {
			GeneralProperties properties = mock(GeneralProperties.class);
			when(properties.isExplorerEnable()).thenReturn(true);
			return properties;
		}

		@Bean
		ApiKeyCoreService apiKeyCoreService() {
			return mock(ApiKeyCoreService.class);
		}

		@Bean
		HmacComponent hmacComponent() {
			return mock(HmacComponent.class);
		}

		@Bean
		ThrottlingFilter throttlingFilter() {
			return mock(ThrottlingFilter.class);
		}

		@Bean
		ThrottlingService throttlingService() {
			ThrottlingService service = mock(ThrottlingService.class);
			when(service.checkGlobalIpLimit(any())).thenReturn(true);
			return service;
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CoreOnlySecurityBeans {

		@Bean
		SecurityProperties securityProperties() {
			return mock(SecurityProperties.class);
		}

		@Bean
		ThrottlingFilter throttlingFilter() {
			return mock(ThrottlingFilter.class);
		}
	}

	private record Fixture(
			AnnotationConfigWebApplicationContext context,
			MockMvc mockMvc,
			Path tokenPath,
			CompletableFuture<ExactOneMiningOutcome> miningResult) implements AutoCloseable {

		@Override
		public void close() {
			context.close();
		}
	}
}
