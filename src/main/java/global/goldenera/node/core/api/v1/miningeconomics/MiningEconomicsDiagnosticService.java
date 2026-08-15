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
package global.goldenera.node.core.api.v1.miningeconomics;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.MiningWindowStateValidation;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateEncoder;
import global.goldenera.cryptoj.serialization.state.networkparams.NetworkParamsStateEncoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateEncoder;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.ActiveConsensusLimits;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.Anchor;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.CanonicalEncoding;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.MiningWindow;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.NetworkParams;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.ValidatorPolicy;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.MiningEconomicsActivationService;
import global.goldenera.node.core.processing.ValidatorMiningViewService;
import global.goldenera.node.core.processing.ValidatorMiningViewService.ValidatorMiningView;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;

/** Captures a bounded mining-economics diagnostic from one canonical head. */
@Service
@RequiredArgsConstructor
public class MiningEconomicsDiagnosticService {

	static final int MAX_CANONICAL_BYTES_IN_RESPONSE = 64 * 1024;
	static final int MAX_VALIDATOR_POLICIES = 2_048;
	private static final String DIGEST_ALGORITHM = "SHA-256";

	private final ChainHeadStateCache chainHeadStateCache;
	private final ChainQuery chainQuery;
	private final EntityIndexRepository entityIndexRepository;
	private final ValidatorMiningViewService validatorMiningViewService;
	private final MiningEconomicsActivationService activationService;
	private final NetworkSettingsProvider networkSettingsProvider;
	private final GeneralProperties generalProperties;

	public MiningEconomicsDiagnosticDtoV1 capture() {
		HeadStateSnapshot snapshot = chainHeadStateCache.getHeadSnapshot();
		StoredBlock anchorBlock = snapshot.head();
		WorldState state = snapshot.state();
		assertStateRoot(anchorBlock, state);

		long height = anchorBlock.getHeight();
		NetworkSettings settings = networkSettingsProvider.currentSettings();
		Network network = generalProperties.getNetwork();
		ForkName activeFork = activeFork(settings, height);
		activationService.assertHeadReady(state, height);

		NetworkParamsState params = state.getParams();
		if (params.getVersion() == null) {
			throw new IllegalStateException("Canonical head has no network parameters state");
		}

		Map<Address, ValidatorState> validators = entityIndexRepository.getAllValidatorsWithAddresses();
		assertValidatorsMatchAnchor(validators, params, state);
		List<ValidatorPolicy> policies = mapPolicies(validators, state);
		MiningWindow miningWindow = mapMiningWindow(state);

		assertAnchorUnchanged(anchorBlock);

		long activeForkHeight = settings.forkActivationBlocks().getOrDefault(activeFork, 0L);
		return new MiningEconomicsDiagnosticDtoV1(
				new Anchor(height, anchorBlock.getHash(), state.getFinalStateRoot()),
				network,
				activeFork,
				activeForkHeight,
				new ActiveConsensusLimits(
						settings.getMaxHeaderSizeInBytes(height),
						settings.getMaxTxSizeInBytes(height),
						settings.getMaxBlockSizeInBytes(height),
						settings.getMaxTxCountPerBlock(height)),
				mapNetworkParams(params),
				List.copyOf(policies),
				miningWindow);
	}

	private List<ValidatorPolicy> mapPolicies(Map<Address, ValidatorState> validators, WorldState state) {
		List<Map.Entry<Address, ValidatorState>> ordered = validators.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(Address::toHexString)))
				.toList();
		List<ValidatorPolicy> result = new ArrayList<>(ordered.size());
		for (Map.Entry<Address, ValidatorState> entry : ordered) {
			Address identity = entry.getKey();
			ValidatorState validator = entry.getValue();
			ValidatorMiningView view = validatorMiningViewService.evaluate(state, identity, validator);
			result.add(new ValidatorPolicy(
					identity,
					validator.getVersion(),
					view.miningPolicySource(),
					view.miningLimitMode(),
					view.maxMiningShareBps(),
					view.maxBlocksInCurrentWindow(),
					view.blocksMinedInCurrentWindow(),
					view.remainingBlocksInCurrentWindow(),
					view.miningEligible(),
					validator.getPolicyUpdatedByTxHash(),
					validator.getVersion() == ValidatorStateVersion.V2
							? validator.getPolicyUpdatedAtBlockHeight()
							: null,
					validator.getPolicyUpdatedAtTimestamp(),
					canonicalEncoding(ValidatorStateEncoder.INSTANCE.encode(validator))));
		}
		return result;
	}

	private MiningWindow mapMiningWindow(WorldState state) {
		if (!state.isMiningWindowTriePresent()) {
			return new MiningWindow(false, null, 0, 0, 0, List.of(), Map.of(), null);
		}

		MiningWindowState window = state.getMiningWindow();
		MiningWindowStateValidation.validate(window);
		List<Address> orderedIdentities = List.copyOf(window.getOrderedValidatorIdentities());
		Map<Address, Long> orderedCounts = new LinkedHashMap<>();
		window.getValidatorBlockCounts().entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(Address::toHexString)))
				.forEach(entry -> orderedCounts.put(entry.getKey(), entry.getValue()));
		Bytes canonical = MiningWindowStateEncoder.INSTANCE.encode(window);
		return new MiningWindow(
				true,
				window.getVersion(),
				window.getWindowSize(),
				orderedIdentities.size(),
				window.getLastUpdatedBlockHeight(),
				orderedIdentities,
				Collections.unmodifiableMap(orderedCounts),
				canonicalEncoding(canonical));
	}

	private NetworkParams mapNetworkParams(NetworkParamsState params) {
		return new NetworkParams(
				params.getVersion(),
				params.getBlockReward(),
				params.getBlockRewardPoolAddress(),
				params.getTargetMiningTimeMs(),
				params.getAsertHalfLifeBlocks(),
				params.getAsertAnchorHeight(),
				params.getMinDifficulty(),
				params.getMinTxBaseFee(),
				params.getMinTxByteFee(),
				params.getCurrentAuthorityCount(),
				params.getCurrentValidatorCount(),
				params.getCurrentUnlimitedValidatorCount(),
				params.getValidatorMiningWindowBlocks(),
				List.copyOf(params.getLimitedValidatorMiningSharesBps()),
				params.getUpdatedByTxHash(),
				params.getUpdatedAtBlockHeight(),
				params.getUpdatedAtTimestamp(),
				canonicalEncoding(NetworkParamsStateEncoder.INSTANCE.encode(params)));
	}

	private void assertValidatorsMatchAnchor(Map<Address, ValidatorState> validators,
			NetworkParamsState params, WorldState state) {
		if (validators.size() > MAX_VALIDATOR_POLICIES) {
			throw new IllegalStateException("Validator policy diagnostic exceeds the response bound");
		}
		if (params.getCurrentValidatorCount() != validators.size()) {
			throw new IllegalStateException("Validator index count does not match the anchored network state");
		}
		for (Map.Entry<Address, ValidatorState> entry : validators.entrySet()) {
			ValidatorState anchored = state.getValidator(entry.getKey());
			if (!anchored.exists()
					|| !ValidatorStateEncoder.INSTANCE.encode(anchored)
							.equals(ValidatorStateEncoder.INSTANCE.encode(entry.getValue()))) {
				throw new IllegalStateException(
						"Validator index does not match the anchored state for " + entry.getKey().toHexString());
			}
		}
	}

	private void assertStateRoot(StoredBlock anchorBlock, WorldState state) {
		Hash headerRoot = anchorBlock.getBlock().getHeader().getStateRootHash();
		if (!headerRoot.equals(state.getFinalStateRoot())) {
			throw new IllegalStateException("Canonical block and WorldState roots do not match");
		}
	}

	private void assertAnchorUnchanged(StoredBlock anchorBlock) {
		StoredBlock current = chainQuery.getLatestStoredBlockOrThrow();
		Hash currentRoot = current.getBlock().getHeader().getStateRootHash();
		Hash anchorRoot = anchorBlock.getBlock().getHeader().getStateRootHash();
		if (current.getHeight() != anchorBlock.getHeight()
				|| !current.getHash().equals(anchorBlock.getHash())
				|| !currentRoot.equals(anchorRoot)) {
			throw new IllegalStateException("Canonical head changed while capturing mining economics diagnostic");
		}
	}

	private ForkName activeFork(NetworkSettings settings, long height) {
		return settings.forkActivationBlocks().entrySet().stream()
				.filter(entry -> entry.getValue() <= height)
				.max(Comparator.<Map.Entry<ForkName, Long>>comparingLong(Map.Entry::getValue)
						.thenComparingInt(entry -> entry.getKey().ordinal()))
				.map(Map.Entry::getKey)
				.orElse(ForkName.GENESIS);
	}

	private CanonicalEncoding canonicalEncoding(Bytes canonical) {
		try {
			byte[] digestBytes = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(canonical.toArrayUnsafe());
			return new CanonicalEncoding(
					DIGEST_ALGORITHM,
					canonical.size(),
					Hash.wrap(digestBytes),
					canonical.size() <= MAX_CANONICAL_BYTES_IN_RESPONSE ? canonical.toHexString() : null);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JVM does not provide SHA-256", e);
		}
	}
}
