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
package global.goldenera.node.shared.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.TxImpl;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipVotePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipVotePayloadImpl;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AddressAliasState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.BipStateMetadata;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AccountNonceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AddressAliasStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.BipStateImpl;
import global.goldenera.cryptoj.common.state.impl.BipStateMetadataImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.node.shared.config.versioning.mixins.blockchain.BlockHeaderMixInV1;
import global.goldenera.node.shared.config.versioning.mixins.blockchain.BlockMixInV1;
import global.goldenera.node.shared.config.versioning.mixins.blockchain.StateMixInsV1;
import global.goldenera.node.shared.config.versioning.mixins.blockchain.TxMixInV1;
import global.goldenera.node.shared.config.versioning.mixins.blockchain.TxPayloadMixInsV1;

@Configuration
public class JacksonConfig {

	@Bean
	@Primary
	public ObjectMapper baseObjectMapper(Jackson2ObjectMapperBuilder builder) {
		ObjectMapper mapper = builder.createXmlMapper(false).build();
		SimpleModule module = new SimpleModule("GoldeneraCryptoModule");

		// ENUMS
		for (Class<? extends Enum<?>> enumClass : GlobalTypeRegistry.CODE_ENUMS) {
			registerReflectionEnum(module, enumClass);
		}

		// CUSTOM TYPES
		for (GlobalTypeRegistry.StringTypeAdapter<?> adapter : GlobalTypeRegistry.STRING_ADAPTERS) {
			registerStringAdapter(module, adapter);
		}

		// JAVA BIG NUMBERS
		module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
		module.addSerializer(BigInteger.class, ToStringSerializer.instance);

		module.addAbstractTypeMapping(Tx.class, TxImpl.class);
		module.addAbstractTypeMapping(BlockHeader.class, BlockHeaderImpl.class);
		module.addAbstractTypeMapping(Block.class, BlockImpl.class);

		module.addAbstractTypeMapping(TxBipAddressAliasAddPayload.class, TxBipAddressAliasAddPayloadImpl.class);
		module.addAbstractTypeMapping(TxBipAddressAliasRemovePayload.class, TxBipAddressAliasRemovePayloadImpl.class);
		module.addAbstractTypeMapping(TxBipAuthorityAddPayload.class, TxBipAuthorityAddPayloadImpl.class);
		module.addAbstractTypeMapping(TxBipAuthorityRemovePayload.class, TxBipAuthorityRemovePayloadImpl.class);
		module.addAbstractTypeMapping(TxBipNetworkParamsSetPayload.class, TxBipNetworkParamsSetPayloadImpl.class);
		module.addAbstractTypeMapping(TxBipTokenBurnPayload.class, TxBipTokenBurnPayloadImpl.class);
		module.addAbstractTypeMapping(TxBipTokenCreatePayload.class, TxBipTokenCreatePayloadImpl.class);
		module.addAbstractTypeMapping(TxBipTokenMintPayload.class, TxBipTokenMintPayloadImpl.class);
		module.addAbstractTypeMapping(TxBipTokenUpdatePayload.class, TxBipTokenUpdatePayloadImpl.class);
		module.addAbstractTypeMapping(TxBipVotePayload.class, TxBipVotePayloadImpl.class);

		module.addAbstractTypeMapping(AccountBalanceState.class, AccountBalanceStateImpl.class);
		module.addAbstractTypeMapping(AccountNonceState.class, AccountNonceStateImpl.class);
		module.addAbstractTypeMapping(AddressAliasState.class, AddressAliasStateImpl.class);
		module.addAbstractTypeMapping(AuthorityState.class, AuthorityStateImpl.class);
		module.addAbstractTypeMapping(BipState.class, BipStateImpl.class);
		module.addAbstractTypeMapping(BipStateMetadata.class, BipStateMetadataImpl.class);
		module.addAbstractTypeMapping(NetworkParamsState.class, NetworkParamsStateImpl.class);
		module.addAbstractTypeMapping(TokenState.class, TokenStateImpl.class);

		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		mapper.registerModule(module);
		return mapper;
	}

	@Bean("jsonV1")
	public ObjectMapper objectMapperV1(ObjectMapper baseMapper) {
		ObjectMapper v1 = baseMapper.copy();

		v1.addMixIn(Tx.class, TxMixInV1.class);
		v1.addMixIn(BlockHeader.class, BlockHeaderMixInV1.class);
		v1.addMixIn(Block.class, BlockMixInV1.class);

		// Base Polymorphic MixIn
		v1.addMixIn(TxPayload.class, TxPayloadMixInsV1.TxPayloadMixIn.class);

		// Concrete Payload MixIns
		v1.addMixIn(TxBipAddressAliasAddPayload.class, TxPayloadMixInsV1.TxBipAddressAliasAddPayloadMixIn.class);
		v1.addMixIn(TxBipAddressAliasRemovePayload.class,
				TxPayloadMixInsV1.TxBipAddressAliasRemovePayloadMixIn.class);

		v1.addMixIn(TxBipAuthorityAddPayload.class, TxPayloadMixInsV1.TxBipAuthorityAddPayloadMixIn.class);
		v1.addMixIn(TxBipAuthorityRemovePayload.class, TxPayloadMixInsV1.TxBipAuthorityRemovePayloadMixIn.class);

		v1.addMixIn(TxBipNetworkParamsSetPayload.class, TxPayloadMixInsV1.TxBipNetworkParamsSetPayloadMixIn.class);

		v1.addMixIn(TxBipTokenBurnPayload.class, TxPayloadMixInsV1.TxBipTokenBurnPayloadMixIn.class);
		v1.addMixIn(TxBipTokenCreatePayload.class, TxPayloadMixInsV1.TxBipTokenCreatePayloadMixIn.class);
		v1.addMixIn(TxBipTokenMintPayload.class, TxPayloadMixInsV1.TxBipTokenMintPayloadMixIn.class);
		v1.addMixIn(TxBipTokenUpdatePayload.class, TxPayloadMixInsV1.TxBipTokenUpdatePayloadMixIn.class);

		v1.addMixIn(TxBipVotePayload.class, TxPayloadMixInsV1.TxBipVotePayloadMixIn.class);

		// Accounts
		v1.addMixIn(AccountBalanceState.class, StateMixInsV1.AccountBalanceStateMixIn.class);
		v1.addMixIn(AccountNonceState.class, StateMixInsV1.AccountNonceStateMixIn.class);
		v1.addMixIn(AddressAliasState.class, StateMixInsV1.AddressAliasStateMixIn.class);
		v1.addMixIn(AuthorityState.class, StateMixInsV1.AuthorityStateMixIn.class);

		// BIPs
		v1.addMixIn(BipState.class, StateMixInsV1.BipStateMixIn.class);
		v1.addMixIn(BipStateMetadata.class, StateMixInsV1.BipStateMetadataMixIn.class);

		// Global & Token
		v1.addMixIn(NetworkParamsState.class, StateMixInsV1.NetworkParamsStateMixIn.class);
		v1.addMixIn(TokenState.class, StateMixInsV1.TokenStateMixIn.class);
		return v1;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void registerStringAdapter(SimpleModule module, GlobalTypeRegistry.StringTypeAdapter adapter) {
		// SERIALIZER (Objekt -> JSON String)
		module.addSerializer(adapter.getType(), new JsonSerializer<Object>() {
			@Override
			public void serialize(Object value, JsonGenerator gen, SerializerProvider s) throws IOException {
				if (value == null) {
					gen.writeNull();
				} else {
					gen.writeString((String) adapter.getToString().apply(value));
				}
			}
		});

		// DESERIALIZER (JSON String -> Objekt)
		module.addDeserializer(adapter.getType(), new JsonDeserializer<Object>() {
			@Override
			public Object deserialize(JsonParser p, DeserializationContext c) throws IOException {
				if (p.getCurrentToken() == JsonToken.VALUE_NULL)
					return null;

				String val = p.getValueAsString();
				if (val == null || val.trim().isEmpty())
					return null;

				try {
					return adapter.getFromString().apply(val);
				} catch (Exception e) {
					return null;
				}
			}
		});
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void registerReflectionEnum(SimpleModule module, Class enumClass) {
		try {
			Method getCodeMethod = enumClass.getMethod("getCode");
			Method fromCodeMethod = enumClass.getMethod("fromCode", int.class);

			module.addSerializer(enumClass, new JsonSerializer() {
				@Override
				public void serialize(Object value, JsonGenerator gen, SerializerProvider s) throws IOException {
					try {
						if (value == null)
							gen.writeNull();
						else
							gen.writeNumber((int) getCodeMethod.invoke(value));
					} catch (Exception e) {
						gen.writeNull();
					}
				}
			});

			module.addDeserializer(enumClass, new JsonDeserializer() {
				@Override
				public Object deserialize(JsonParser p, DeserializationContext c) throws IOException {
					if (p.getCurrentToken() == JsonToken.VALUE_NULL)
						return null;
					try {
						return fromCodeMethod.invoke(null, p.getValueAsInt());
					} catch (Exception e) {
						return null;
					}
				}
			});

		} catch (NoSuchMethodException e) {
			throw new RuntimeException("Error with registering enum: " + enumClass.getSimpleName(), e);
		}
	}

}