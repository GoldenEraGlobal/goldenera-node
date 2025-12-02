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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

@Configuration
public class JacksonConfig {

	@Bean
	public Module goldeneraCryptoModule() {
		SimpleModule module = new SimpleModule("GoldeneraCryptoModule");

		// 1. ENUMS
		for (Class<? extends Enum<?>> enumClass : GlobalTypeRegistry.CODE_ENUMS) {
			registerReflectionEnum(module, enumClass);
		}

		// 2. CUSTOM TYPES
		for (GlobalTypeRegistry.StringTypeAdapter<?> adapter : GlobalTypeRegistry.STRING_ADAPTERS) {
			registerStringAdapter(module, adapter);
		}

		// 3. JAVA BIG NUMBERS
		module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
		module.addSerializer(BigInteger.class, ToStringSerializer.instance);

		return module;
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