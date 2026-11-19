package com.tiendaposs.catalog;

import com.tiendaposs.common.ApiException;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;

/**
 * Plantilla y archivo de productos en Excel (.xlsx): una tabla de verdad con encabezados en español,
 * listas desplegables y una hoja de instrucciones. Al importar se aceptan tanto estos encabezados
 * como los nombres técnicos del CSV (sku, nombre, codigo_barras…).
 */
final class ProductXlsx {

    /** Encabezado visible de cada columna (mismo orden que {@link ProductCsvService#HEADERS}). */
    static final Map<String, String> LABELS = new LinkedHashMap<>();
    /** Explicación y ejemplo de cada columna para la hoja de instrucciones. */
    private static final Map<String, String[]> HELP = new LinkedHashMap<>();

    static {
        col("sku", "SKU (código interno)", "Opcional. Si ya existe un producto con ese SKU, se actualiza; si lo dejas vacío, se crea uno nuevo.", "CER-001");
        col("nombre", "Nombre *", "Obligatorio. Nombre como lo verás en la caja.", "Cerveza Águila 330ml lata");
        col("categoria", "Categoría", "Opcional. Si no existe, se crea.", "Cervezas");
        col("subcategoria", "Subcategoría", "Opcional. Va dentro de la categoría.", "Nacionales");
        col("marca", "Marca", "Opcional.", "Bavaria");
        col("unidad", "Unidad", "Cómo se vende: UNIDAD, KILOGRAMO, GRAMO, LITRO, MILILITRO, METRO o LIBRA. Si la dejas vacía queda UNIDAD.", "UNIDAD");
        col("codigo_barras", "Código de barras", "Opcional. El código que lee el lector.", "7702004003508");
        col("precio", "Precio de venta *", "Obligatorio. Precio de una unidad, sin puntos ni signo $.", "3500");
        col("precio_minimo", "Precio mínimo", "Opcional. El menor precio al que se puede vender con descuento.", "3200");
        col("costo", "Costo", "Opcional. Lo que te cuesta a ti.", "2400");
        col("stock", "Existencias", "Opcional. Cuántas unidades tienes hoy.", "48");
        col("stock_minimo", "Stock mínimo", "Opcional. Por debajo de este número te avisa que pidas.", "24");
        col("stock_maximo", "Stock máximo", "Opcional. Lo máximo que quieres tener.", "120");
        col("stock_seguridad", "Stock de seguridad", "Opcional. Reserva para no quedarte sin producto.", "12");
        col("impuesto", "Impuesto", "Opcional. Nombre del impuesto: IVA, INC, Exento…", "IVA");
        col("tasa_impuesto", "Tarifa de impuesto (%)", "Opcional. Porcentaje: 0, 5, 19…", "19");
        col("impuesto_fijo", "Impuesto fijo ($)", "Opcional. Valor fijo por unidad (por ejemplo impuesto a bolsas o licores).", "0");
        col("estado", "Estado", "activo o descontinuado. Si lo dejas vacío queda activo.", "activo");
        col("presentaciones", "Presentaciones", "Opcional. Otras formas de venderlo, separadas por |. Cada una: Nombre:cuántas unidades trae:precio:código de barras.",
                "Six pack:6:20000:|Caja x24:24:78000:");
    }

    private static final Set<String> TEXT_COLS = Set.of("sku", "codigo_barras", "presentaciones", "nombre", "categoria", "subcategoria", "marca");
    private static final Set<String> MONEY_COLS = Set.of("precio", "precio_minimo", "costo", "impuesto_fijo");
    private static final Set<String> REQUIRED = Set.of("nombre", "precio");
    private static final String[] UNITS = {"UNIDAD", "KILOGRAMO", "GRAMO", "LITRO", "MILILITRO", "METRO", "LIBRA"};
    private static final String[] STATES = {"activo", "descontinuado"};
    private static final int TEMPLATE_ROWS = 2000;

    private ProductXlsx() {
    }

    private static void col(String key, String label, String help, String example) {
        LABELS.put(key, label);
        HELP.put(key, new String[]{help, example});
    }

    // ---------------- escribir ----------------

    /** Libro con la tabla de productos. {@code rows} no incluye el encabezado. */
    static byte[] write(List<List<String>> rows, boolean template) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> keys = ProductCsvService.HEADERS;
            XSSFSheet sh = wb.createSheet("Productos");
            DataFormat fmt = wb.createDataFormat();

            XSSFCellStyle head = headerStyle(wb, new byte[]{(byte) 0x1F, (byte) 0x4E, (byte) 0x3D});
            XSSFCellStyle headReq = headerStyle(wb, new byte[]{(byte) 0xC2, (byte) 0x41, (byte) 0x0C});
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(fmt.getFormat("@"));
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0"));
            CellStyle number = wb.createCellStyle();
            number.setDataFormat(fmt.getFormat("#,##0.###"));

            Row h = sh.createRow(0);
            h.setHeightInPoints(34);
            for (int c = 0; c < keys.size(); c++) {
                String k = keys.get(c);
                Cell cell = h.createCell(c);
                cell.setCellValue(LABELS.get(k));
                cell.setCellStyle(REQUIRED.contains(k) ? headReq : head);
                CellStyle colStyle = TEXT_COLS.contains(k) || k.equals("unidad") || k.equals("impuesto") || k.equals("estado") ? text
                        : MONEY_COLS.contains(k) ? money : number;
                sh.setDefaultColumnStyle(c, colStyle);
                int width = switch (k) {
                    case "nombre" -> 34;
                    case "presentaciones" -> 40;
                    case "codigo_barras", "sku", "tasa_impuesto", "stock_seguridad" -> 20;
                    case "categoria", "subcategoria", "marca", "precio", "impuesto_fijo" -> 17;
                    default -> 15;
                };
                sh.setColumnWidth(c, width * 256);
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.createRow(r + 1);
                List<String> vals = rows.get(r);
                for (int c = 0; c < keys.size(); c++) {
                    String k = keys.get(c);
                    String v = c < vals.size() ? vals.get(c) : "";
                    Cell cell = row.createCell(c);
                    BigDecimal n = TEXT_COLS.contains(k) || v == null || v.isBlank() ? null : asNumber(v);
                    if (n != null) {
                        cell.setCellValue(n.doubleValue());
                        cell.setCellStyle(MONEY_COLS.contains(k) ? money : number);
                    } else {
                        cell.setCellValue(v == null ? "" : v);
                        cell.setCellStyle(text);
                    }
                }
            }

            // Tabla de Excel con filas en franjas y filtros; crece sola al escribir debajo.
            int last = Math.max(rows.size(), 1);
            if (rows.isEmpty()) sh.createRow(1);
            AreaReference area = new AreaReference(new CellReference(0, 0), new CellReference(last, keys.size() - 1), SpreadsheetVersion.EXCEL2007);
            XSSFTable table = sh.createTable(area);
            table.setName("TablaProductos");
            table.setDisplayName("TablaProductos");
            table.getCTTable().addNewTableStyleInfo();
            XSSFTableStyleInfo style = (XSSFTableStyleInfo) table.getStyle();
            style.setName("TableStyleMedium7");
            style.setShowRowStripes(true);
            for (int c = 0; c < keys.size(); c++) table.getCTTable().getTableColumns().getTableColumnArray(c).setName(LABELS.get(keys.get(c)));
            table.getCTTable().addNewAutoFilter().setRef(area.formatAsString());

            sh.createFreezePane(2, 1);
            int maxRow = Math.max(TEMPLATE_ROWS, rows.size() + 500);
            list(sh, keys.indexOf("unidad"), maxRow, UNITS, "Unidad", "Elige cómo se vende el producto.");
            list(sh, keys.indexOf("estado"), maxRow, STATES, "Estado", "activo o descontinuado.");

            instructions(wb, head, template);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel", e);
        }
    }

    private static XSSFCellStyle headerStyle(XSSFWorkbook wb, byte[] rgb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(rgb, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        XSSFFont f = wb.createFont();
        f.setBold(true);
        f.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
        s.setFont(f);
        return s;
    }

    private static void list(XSSFSheet sh, int col, int maxRow, String[] values, String title, String msg) {
        DataValidationHelper dv = sh.getDataValidationHelper();
        DataValidation v = dv.createValidation(dv.createExplicitListConstraint(values), new CellRangeAddressList(1, maxRow, col, col));
        v.setShowErrorBox(true);
        v.setErrorStyle(DataValidation.ErrorStyle.STOP);
        v.createErrorBox(title, "Elige un valor de la lista.");
        v.setShowPromptBox(true);
        v.createPromptBox(title, msg);
        v.setEmptyCellAllowed(true);
        sh.addValidationData(v);
    }

    private static void instructions(XSSFWorkbook wb, XSSFCellStyle head, boolean template) {
        XSSFSheet sh = wb.createSheet("Instrucciones");
        CellStyle wrap = wb.createCellStyle();
        wrap.setWrapText(true);
        wrap.setVerticalAlignment(VerticalAlignment.TOP);
        XSSFCellStyle title = wb.createCellStyle();
        XSSFFont tf = wb.createFont();
        tf.setBold(true);
        tf.setFontHeightInPoints((short) 14);
        title.setFont(tf);
        XSSFCellStyle bold = wb.createCellStyle();
        XSSFFont bf = wb.createFont();
        bf.setBold(true);
        bold.setFont(bf);
        bold.setVerticalAlignment(VerticalAlignment.TOP);

        int r = 0;
        sh.createRow(r++).createCell(0).setCellValue("Cómo llenar la plantilla de productos");
        sh.getRow(0).getCell(0).setCellStyle(title);
        String[] steps = {
                "1. Ve a la hoja \"Productos\" y escribe un producto por fila, debajo de los encabezados.",
                template ? "2. La primera fila es un ejemplo: cámbiala por tu producto o bórrala." : "2. Cambia lo que necesites; si el SKU ya existe, el producto se actualiza.",
                "3. Las columnas en naranja (Nombre y Precio de venta) son obligatorias. Las demás puedes dejarlas vacías.",
                "4. Escribe los precios sin puntos ni signo $ (por ejemplo 3500).",
                "5. Guarda el archivo tal cual (Excel .xlsx) y súbelo en Inventario → Importar.",
        };
        for (String s : steps) sh.createRow(r++).createCell(0).setCellValue(s);
        r++;
        Row h = sh.createRow(r++);
        String[] cols = {"Columna", "Qué va", "Ejemplo"};
        for (int c = 0; c < cols.length; c++) {
            h.createCell(c).setCellValue(cols[c]);
            h.getCell(c).setCellStyle(head);
        }
        for (Map.Entry<String, String[]> e : HELP.entrySet()) {
            Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(LABELS.get(e.getKey()));
            row.getCell(0).setCellStyle(bold);
            row.createCell(1).setCellValue(e.getValue()[0]);
            row.getCell(1).setCellStyle(wrap);
            row.createCell(2).setCellValue(e.getValue()[1]);
            row.getCell(2).setCellStyle(wrap);
        }
        sh.setColumnWidth(0, 26 * 256);
        sh.setColumnWidth(1, 80 * 256);
        sh.setColumnWidth(2, 36 * 256);
    }

    private static BigDecimal asNumber(String v) {
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------- leer ----------------

    /** Lee la primera hoja (o la llamada "Productos") como tabla de texto; la fila 0 es el encabezado con claves técnicas. */
    static List<List<String>> read(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sh = wb.getSheet("Productos") != null ? wb.getSheet("Productos") : wb.getSheetAt(0);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter df = new DataFormatter(Locale.ROOT);
            List<List<String>> out = new ArrayList<>();
            int headerRow = -1;
            for (int r = sh.getFirstRowNum(); r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                int lastCol = Math.max(row.getLastCellNum(), 0);
                for (int c = 0; c < lastCol; c++) cells.add(value(row.getCell(c), eval, df));
                if (cells.stream().allMatch(String::isBlank)) continue;
                if (headerRow < 0) {
                    headerRow = r;
                    cells = cells.stream().map(ProductXlsx::keyFor).toList();
                }
                out.add(cells);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw ApiException.badRequest("No se pudo leer el archivo de Excel. Guárdalo como .xlsx e inténtalo de nuevo.");
        }
    }

    private static String value(Cell cell, FormulaEvaluator eval, DataFormatter df) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? df.formatCellValue(cell, eval) : plain(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** Número sin notación científica (los códigos de barras quedan completos). Si tiene justo 3 decimales se le
     * agrega un cero para que el importador no lo confunda con miles ("1.234" kg ≠ 1234). */
    private static String plain(double d) {
        String s = new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
        if (s.matches("\\d{1,3}(\\.\\d{3})+")) s = s + "0";
        return s;
    }

    /** "Código de barras" → "codigo_barras"; también acepta las claves técnicas tal cual. */
    static String keyFor(String header) {
        String n = norm(header);
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (n.equals(norm(e.getKey())) || n.equals(norm(e.getValue()))) return e.getKey();
        }
        return header.trim().toLowerCase();
    }

    private static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
