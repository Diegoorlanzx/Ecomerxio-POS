package com.tiendaposs.fiscal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Municipios de Colombia (código DANE) que acepta el proveedor; la lista está en resources/fiscal/municipios.csv. */
public final class Municipalities {

    public record Municipality(String code, String name, String department) {
    }

    private static final Map<String, Municipality> BY_CODE = load();

    private Municipalities() {
    }

    public static boolean exists(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    public static Municipality get(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    private static Map<String, Municipality> load() {
        Map<String, Municipality> m = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(Municipalities.class.getResourceAsStream("/fiscal/municipios.csv")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split(";", 3);
                m.put(p[0], new Municipality(p[0], p[1], p[2]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(m);
    }
}
