package dev.avelar.mapnik;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;

/** Reads Mapnik's typed key/value parameters into a Java map, for maps and datasources alike. */
final class ParamValues {
    private ParamValues() {}

    static Map<String, Object> read(int count, IntFunction<String> name, IntUnaryOperator type, IntPredicate bool,
                                    IntToLongFunction integer, IntToDoubleFunction dbl, IntFunction<String> string) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Object v;
            switch (type.applyAsInt(i)) {
                case 0:
                    v = null;
                    break;
                case 1:
                    v = bool.test(i);
                    break;
                case 2:
                    v = integer.applyAsLong(i);
                    break;
                case 3:
                    v = dbl.applyAsDouble(i);
                    break;
                default:
                    v = string.apply(i);
            }
            out.put(name.apply(i), v);
        }
        return Collections.unmodifiableMap(out);
    }

    /** True if {@code value} is a type Mapnik parameters can hold. */
    static boolean supported(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Integer
            || value instanceof Long || value instanceof Short || value instanceof Byte
            || value instanceof Double || value instanceof Float;
    }
}
