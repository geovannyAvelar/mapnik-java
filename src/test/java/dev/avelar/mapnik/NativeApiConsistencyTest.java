package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link NativeApi} matches native/mapnik_c.h: same functions, same number of
 * parameters, compatible types. A mismatch does not fail cleanly at run time; JNA passes garbage
 * and the JVM crashes. This test needs no native library.
 */
class NativeApiConsistencyTest {

    private static final class CFunction {
        final String name;
        final String returnType;
        final List<String> params;

        CFunction(String name, String returnType, List<String> params) {
            this.name = name;
            this.returnType = returnType;
            this.params = params;
        }

        @Override
        public String toString() {
            return returnType + " " + name + params;
        }
    }

    private static Map<String, CFunction> parseHeader() throws IOException {
        String path = System.getProperty("native.header");
        assertNotNull(path, "native.header system property not set");
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        text = text.replaceAll("(?s)/\\*.*?\\*/", " ");       // comments
        text = text.replaceAll("(?m)^\\s*#.*$", " ");          // preprocessor lines
        text = text.replace("extern \"C\" {", " ").replace("}", " ");

        Pattern fn = Pattern.compile("^(.*?)\\b(mapnik_\\w+)\\s*\\((.*)\\)$", Pattern.DOTALL);
        Map<String, CFunction> out = new HashMap<>();
        for (String decl : text.split(";")) {
            String d = decl.trim().replaceAll("\\s+", " ");
            if (d.isEmpty() || d.startsWith("typedef")) {
                continue;
            }
            Matcher m = fn.matcher(d);
            assertTrue(m.matches(), "cannot parse declaration: " + d);
            List<String> params = new ArrayList<>();
            String args = m.group(3).trim();
            if (!args.isEmpty() && !args.equals("void")) {
                for (String a : args.split(",")) {
                    params.add(paramType(a.trim()));
                }
            }
            out.put(m.group(2), new CFunction(m.group(2), normalize(m.group(1)), params));
        }
        return out;
    }

    /** The type of one parameter: drop the trailing name. */
    private static String paramType(String param) {
        String p = param.trim();
        // "const char* const* property_names" -> "const char* const*"
        Matcher m = Pattern.compile("^(.*?[\\s\\*])(\\w+)$").matcher(p);
        return normalize(m.matches() ? m.group(1) : p);
    }

    private static String normalize(String t) {
        return t.replace("const ", "").replace(" const", "").replaceAll("\\s+", " ").replace(" *", "*").trim();
    }

    private static boolean compatible(String c, Class<?> j) {
        if (c.matches("mapnik_\\w+_t\\*")) {
            return j == Pointer.class;
        }
        if (c.matches("mapnik_\\w+_t\\*\\*")) {
            return j == PointerByReference.class;
        }
        switch (c) {
            case "void": return j == void.class;
            case "int": return j == int.class;
            case "double": return j == double.class;
            case "long long": return j == long.class;
            case "char*": return j == String.class;
            case "char**": return j == String[].class;
            case "double*": return j == double[].class;
            case "int*": return j == int[].class || j == IntByReference.class;
            case "unsigned char*": return j == Pointer.class;
            case "unsigned char**": return j == PointerByReference.class;
            default: return false;
        }
    }

    @Test
    void javaMappingMatchesTheHeader() throws Exception {
        Map<String, CFunction> header = parseHeader();
        assertTrue(header.size() > 100, "parsed only " + header.size() + " functions");

        Map<String, Method> java = new HashMap<>();
        for (Method m : NativeApi.class.getDeclaredMethods()) {
            if (m.isSynthetic() || java.put(m.getName(), m) != null) {
                fail("overloaded or synthetic method in NativeApi: " + m.getName());
            }
        }

        Set<String> missingInJava = new HashSet<>(header.keySet());
        missingInJava.removeAll(java.keySet());
        assertTrue(missingInJava.isEmpty(), "in the header but not in NativeApi: " + missingInJava);

        Set<String> missingInHeader = new HashSet<>(java.keySet());
        missingInHeader.removeAll(header.keySet());
        assertTrue(missingInHeader.isEmpty(), "in NativeApi but not in the header: " + missingInHeader);

        List<String> problems = new ArrayList<>();
        for (CFunction f : header.values()) {
            Method m = java.get(f.name);
            Class<?>[] jp = m.getParameterTypes();
            if (jp.length != f.params.size()) {
                problems.add(f.name + ": C has " + f.params.size() + " parameters " + f.params
                    + ", Java has " + jp.length + " " + Arrays.toString(jp));
                continue;
            }
            for (int i = 0; i < jp.length; i++) {
                if (!compatible(f.params.get(i), jp[i])) {
                    problems.add(f.name + ": parameter " + i + " is C '" + f.params.get(i)
                        + "' but Java " + jp[i].getSimpleName());
                }
            }
            if (!compatible(f.returnType, m.getReturnType())) {
                problems.add(f.name + ": returns C '" + f.returnType + "' but Java " + m.getReturnType().getSimpleName());
            }
        }
        assertTrue(problems.isEmpty(), "NativeApi does not match mapnik_c.h:\n  " + String.join("\n  ", problems));
    }

    @Test
    void thePreviousBugWouldBeCaught() {
        // The shape of the mistake that crashed the JVM: C takes (p, x, y) but Java passed (p, xy).
        assertFalse(compatible("double*", double.class));
        assertFalse(compatible("mapnik_map_t*", String.class));
        assertFalse(compatible("int", long.class));
    }
}
