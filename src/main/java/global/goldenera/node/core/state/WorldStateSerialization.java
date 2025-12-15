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
package global.goldenera.node.core.state;

import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.serialization.state.accountbalance.AccountBalanceStateDecoder;
import global.goldenera.cryptoj.serialization.state.accountbalance.AccountBalanceStateEncoder;
import global.goldenera.cryptoj.serialization.state.accountnonce.AccountNonceStateDecoder;
import global.goldenera.cryptoj.serialization.state.accountnonce.AccountNonceStateEncoder;
import global.goldenera.cryptoj.serialization.state.addressalias.AddressAliasStateDecoder;
import global.goldenera.cryptoj.serialization.state.addressalias.AddressAliasStateEncoder;
import global.goldenera.cryptoj.serialization.state.authority.AuthorityStateDecoder;
import global.goldenera.cryptoj.serialization.state.authority.AuthorityStateEncoder;
import global.goldenera.cryptoj.serialization.state.bip.BipStateDecoder;
import global.goldenera.cryptoj.serialization.state.bip.BipStateEncoder;
import global.goldenera.cryptoj.serialization.state.networkparams.NetworkParamsStateDecoder;
import global.goldenera.cryptoj.serialization.state.networkparams.NetworkParamsStateEncoder;
import global.goldenera.cryptoj.serialization.state.token.TokenStateDecoder;
import global.goldenera.cryptoj.serialization.state.token.TokenStateEncoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateDecoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateEncoder;

@Configuration
public class WorldStateSerialization {

	@Bean
	public Function<Bytes, Bytes> rootStateSerializer() {
		return Function.identity();
	}

	@Bean
	public Function<Bytes, Bytes> rootStateDeserializer() {
		return Function.identity();
	}

	@Bean
	public Function<AccountBalanceState, Bytes> balanceSerializer() {
		return AccountBalanceStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, AccountBalanceState> balanceDeserializer() {
		return AccountBalanceStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<AccountNonceState, Bytes> nonceSerializer() {
		return AccountNonceStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, AccountNonceState> nonceDeserializer() {
		return AccountNonceStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<AddressAliasState, Bytes> addressAliasSerializer() {
		return AddressAliasStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, AddressAliasState> addressAliasDeserializer() {
		return AddressAliasStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<AuthorityState, Bytes> authoritySerializer() {
		return AuthorityStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, AuthorityState> authorityDeserializer() {
		return AuthorityStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<ValidatorState, Bytes> validatorSerializer() {
		return ValidatorStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, ValidatorState> validatorDeserializer() {
		return ValidatorStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<BipState, Bytes> bipStateSerializer() {
		return BipStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, BipState> bipStateDeserializer() {
		return BipStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<NetworkParamsState, Bytes> networkParamsSerializer() {
		return NetworkParamsStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, NetworkParamsState> networkParamsDeserializer() {
		return NetworkParamsStateDecoder.INSTANCE::decode;
	}

	@Bean
	public Function<TokenState, Bytes> tokenSerializer() {
		return TokenStateEncoder.INSTANCE::encode;
	}

	@Bean
	public Function<Bytes, TokenState> tokenDeserializer() {
		return TokenStateDecoder.INSTANCE::decode;
	}
}
