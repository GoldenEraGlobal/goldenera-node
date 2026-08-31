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
package global.goldenera.node.core.storage.chainidentity;

import org.springframework.beans.factory.InitializingBean;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory.GenesisCandidate;
import global.goldenera.node.core.blockchain.genesis.SandboxGenesisPlan;
import global.goldenera.node.core.blockchain.genesis.SandboxGenesisPlanFactory;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

/** Local genesis proof and authoritative Rocks binding authorization boundary. */
public final class ChainIdentityGenesisVerifier implements InitializingBean, ChainIdentityBindingVerifier {

	public static final String BEAN_NAME = "chainIdentityGenesisVerifier";

	private final SandboxRuntimeContext runtimeContext;
	private final NetworkSettingsProvider networkSettingsProvider;
	private final ExpectedChainIdentityProvider expectedIdentityProvider;
	private final SandboxGenesisPlanFactory sandboxPlanFactory;
	private final GenesisCandidateFactory candidateFactory;

	private ChainIdentityExpectation verifiedExpectation;
	private SandboxGenesisPlan sandboxPlan;
	private VerifiedGenesisPlan verifiedGenesisPlan;
	private boolean bindingAuthorized;

	public ChainIdentityGenesisVerifier(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			ExpectedChainIdentityProvider expectedIdentityProvider,
			SandboxGenesisPlanFactory sandboxPlanFactory,
			GenesisCandidateFactory candidateFactory) {
		this.runtimeContext = runtimeContext;
		this.networkSettingsProvider = networkSettingsProvider;
		this.expectedIdentityProvider = expectedIdentityProvider;
		this.sandboxPlanFactory = sandboxPlanFactory;
		this.candidateFactory = candidateFactory;
	}

	@Override
	public synchronized void afterPropertiesSet() {
		ChainIdentityExpectation expected = expectedIdentityProvider.expectedIdentity();
		if (expected.sandbox()) {
			SandboxManifestContext manifest = runtimeContext.manifestContext().orElseThrow();
			SandboxGenesisPlan verifiedPlan = sandboxPlanFactory.createVerified(manifest);
			assertPlanIntegrity(verifiedPlan);
			assertExactIdentity(expected, identityOf(verifiedPlan));
			sandboxPlan = verifiedPlan;
		} else if (expected.scope() == ChainIdentityExecutionScope.DEVELOPMENT) {
			Block recalculated = candidateFactory.create(networkSettingsProvider.currentSettings(), 0L).block();
			assertExpectedGenesisHash(expected, recalculated);
		}
		verifiedExpectation = expected;
	}

	@Override
	public synchronized void verifyBeforeBinding(ChainIdentityExpectation expectation) {
		if (verifiedExpectation == null || !verifiedExpectation.equals(expectation)) {
			throw new ChainStorageGuardException(
					"Chain identity binding is not authorized by a matching local genesis verification");
		}
		if (expectation.sandbox()) {
			assertPlanIntegrity(sandboxPlan);
			assertExactIdentity(expectation, identityOf(sandboxPlan));
		}
		bindingAuthorized = true;
	}

	public synchronized VerifiedGenesisPlan verifiedGenesisPlan() {
		if (verifiedExpectation == null || !bindingAuthorized) {
			throw new ChainStorageGuardException(
					"Genesis plan was requested before identity binding authorization");
		}
		if (verifiedGenesisPlan == null) {
			GenesisCandidate candidate;
			Address authority;
			if (sandboxPlan != null) {
				assertPlanIntegrity(sandboxPlan);
				candidate = new GenesisCandidate(sandboxPlan.worldState(), sandboxPlan.genesisBlock());
				authority = sandboxPlan.configuration().networkSettings()
						.genesisAuthorityAddresses().stream().findFirst().orElseThrow(() ->
								new ChainStorageGuardException("Verified sandbox genesis has no authority"));
			} else {
				candidate = candidateFactory.create(networkSettingsProvider.currentSettings(), 0L);
				authority = networkSettingsProvider.currentSettings().genesisAuthorityAddresses().stream()
						.findFirst().orElseThrow(() ->
								new ChainStorageGuardException("Verified genesis has no authority"));
			}
			assertExpectedGenesisHash(verifiedExpectation, candidate.block());
			verifiedGenesisPlan = new VerifiedGenesisPlan(
					candidate.block(), candidate.worldState(), authority,
					verifiedExpectation.identity().genesisHash());
		}
		return verifiedGenesisPlan;
	}

	private void assertExpectedGenesisHash(ChainIdentityExpectation expected, Block block) {
		if (!expected.identity().genesisHash().equals(block.getHash().toHexString())) {
			throw new ChainStorageGuardException("Locally calculated genesis changed after identity preflight");
		}
	}

	private StoredChainIdentity identityOf(SandboxGenesisPlan plan) {
		return new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				plan.configuration().legacyCarrier().getCode(),
				plan.configuration().chainId(),
				plan.genesisBlock().getHash().toHexString(),
				plan.configuration().manifestFingerprint());
	}

	private void assertExactIdentity(ChainIdentityExpectation expected, StoredChainIdentity actual) {
		if (!expected.identity().equals(actual)) {
			throw new ChainStorageGuardException(
					"Locally verified genesis identity does not exactly match the preflight expectation");
		}
	}

	private void assertPlanIntegrity(SandboxGenesisPlan plan) {
		if (plan == null) {
			throw new ChainStorageGuardException("Locally verified sandbox genesis plan is unavailable");
		}
		Hash stateRoot = plan.worldState().calculateRootHash();
		if (!stateRoot.equals(plan.genesisBlock().getHeader().getStateRootHash())) {
			throw new ChainStorageGuardException("Sandbox genesis world state changed after verification");
		}
		if (!plan.genesisBlock().getHash().equals(plan.configuration().expectedGenesisHash())) {
			throw new ChainStorageGuardException("Sandbox genesis block changed after verification");
		}
	}
}
