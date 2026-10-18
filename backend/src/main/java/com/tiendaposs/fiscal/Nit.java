package com.tiendaposs.fiscal;

/** Dígito de verificación del NIT (módulo 11 con los pesos de la DIAN). */
public final class Nit {

    private static final int[] WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    private Nit() {
    }

    /** Solo los dígitos del número (sin puntos, guiones ni el DV). */
    public static String digits(String number) {
        return number == null ? "" : number.replaceAll("[^0-9]", "");
    }

    /** DV del número; null si no es un número válido (vacío o más de 15 dígitos). */
    public static Character dv(String number) {
        String d = digits(number);
        if (d.isEmpty() || d.length() > WEIGHTS.length) return null;
        int sum = 0;
        for (int i = 0; i < d.length(); i++) {
            sum += (d.charAt(d.length() - 1 - i) - '0') * WEIGHTS[i];
        }
        int r = sum % 11;
        return (char) ('0' + (r > 1 ? 11 - r : r));
    }

    public static boolean matches(String number, String dv) {
        Character expected = dv(number);
        return expected != null && dv != null && dv.trim().length() == 1 && dv.trim().charAt(0) == expected;
    }
}
