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
package global.goldenera.node.shared.converters;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.springframework.core.convert.converter.Converter;

public class ReflectionEnumConverter<T extends Enum<T>> implements Converter<String, T> {

    private final MethodHandle fromCodeHandle;
    private final Class<T> enumType;

    public ReflectionEnumConverter(Class<T> enumType) {
        this.enumType = enumType;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType mt = MethodType.methodType(enumType, int.class);
            this.fromCodeHandle = lookup.findStatic(enumType, "fromCode", mt);

        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Enum " + enumType.getName() + " does not have public static fromCode(int)", e);
        }
    }

    @Override
    public T convert(String source) {
        if (source.isEmpty())
            return null;
        try {
            int code = Integer.parseInt(source);
            return (T) fromCodeHandle.invoke(code);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value must be a number: " + source);
        } catch (Throwable e) {
            throw new RuntimeException("Error converting " + enumType.getSimpleName(), e);
        }
    }
}