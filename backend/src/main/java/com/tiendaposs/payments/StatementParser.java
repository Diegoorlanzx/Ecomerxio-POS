package com.tiendaposs.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lee extractos en CSV de bancos y billeteras sin plantilla fija: detecta el separador y las
 * columnas por su nombre (fecha, valor/monto/crédito, referencia, descripción/detalle) y toma solo
 * las entradas de dinero (valores positivos o columna de crédito).
 */
public final class StatementParser {

    public record Row(LocalDate date, BigDecimal amount, String reference, String description) {
    }

    private StatementParser() {
    }

    public static List<Row> parse(String csv) {
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() < 2) return List.of();
        String first = lines.get(0);
        char sep = count(first, ';') >= count(first, ',') ? (count(first, ';') > 0 ? ';' : (count(first, '\t') > 0 ? '\t' : ',')) : ',';
        List<String> head = split(first, sep).stream().map(h -> norm(h)).toList();
        int date = find(head, "fecha", "date", "fecha transaccion", "fecha movimiento");
        int amount = find(head, "valor", "monto", "importe", "amount", "credito", "abono", "entrada", "valor transaccion");
        int credit = find(head, "credito", "abono", "entrada", "ingreso");
        int debit = find(head, "debito", "cargo", "salida", "egreso");
        int ref = find(head, "referencia", "reference", "ref", "id transaccion", "numero", "comprobante", "codigo");
        int desc = find(head, "descripcion", "detalle", "concepto", "description", "nombre", "remitente");
        if (amount < 0 && credit < 0) throw new IllegalArgumentException("sin columna de valor");
        List<Row> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> c = split(lines.get(i), sep);
            BigDecimal v = credit >= 0 ? money(get(c, credit)) : money(get(c, amount));
            if (v == null && amount >= 0) v = money(get(c, amount));
            if (v == null || v.signum() <= 0) continue;
            if (debit >= 0 && credit < 0) {
                BigDecimal d = money(get(c, debit));
                if (d != null && d.signum() > 0) continue;
            }
            String r = ref >= 0 ? emptyNull(get(c, ref)) : null;
            String d = desc >= 0 ? emptyNull(get(c, desc)) : null;
            if (r == null && d != null) r = extractRef(d);
            out.add(new Row(date >= 0 ? date(get(c, date)) : null, v, r, d));
        }
        return out;
    }

    /** Busca algo con pinta de referencia (6+ caracteres alfanuméricos con dígitos) dentro de la descripción. */
    static String extractRef(String d) {
        for (String w : d.split("[\\s,;:#]+")) {
            if (w.length() >= 6 && w.matches(".*\\d.*") && w.matches("[A-Za-z0-9-]+")) return w;
        }
        return null;
    }

    private static int find(List<String> head, String... names) {
        for (String n : names) for (int i = 0; i < head.size(); i++) if (head.get(i).equals(n)) return i;
        for (String n : names) for (int i = 0; i < head.size(); i++) if (head.get(i).contains(n)) return i;
        return -1;
    }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("\"", "");
    }

    private static BigDecimal money(String s) {
        if (s == null) return null;
        String x = s.replace("$", "").replace("COP", "").replace(" ", "").replace(" ", "").trim();
        if (x.isEmpty()) return null;
        boolean neg = x.startsWith("-") || (x.startsWith("(") && x.endsWith(")"));
        x = x.replace("-", "").replace("(", "").replace(")", "").replace("+", "");
        int lastDot = x.lastIndexOf('.'), lastComma = x.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) x = x.replace(".", "").replace(',', '.');
            else x = x.replace(",", "");
        } else if (lastComma >= 0) {
            x = x.length() - lastComma == 3 ? x.replace(',', '.') : x.replace(",", "");
        } else if (lastDot >= 0 && x.length() - lastDot == 4) {
            x = x.replace(".", "");
        }
        try {
            BigDecimal v = new BigDecimal(x);
            return neg ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        String x = s.trim().split("[ T]")[0];
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(x, f);
            } catch (Exception ignored) {
                // siguiente formato
            }
        }
        return null;
    }

    private static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static String get(List<String> c, int i) {
        return i >= 0 && i < c.size() ? c.get(i) : null;
    }

    private static String emptyNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }
}
