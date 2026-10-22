package com.tiendaposs.purchasing;

import com.tiendaposs.common.ApiException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lee la factura electrónica que manda un proveedor (UBL 2.1 de la DIAN). Acepta:
 * <ul>
 *   <li>el contenedor {@code AttachedDocument} que llega por correo (la factura va adentro, como texto);</li>
 *   <li>la {@code Invoice} sola;</li>
 *   <li>el ZIP del correo (con el XML y el PDF): se toma el XML.</li>
 * </ul>
 * Se busca por nombre local de los elementos (sin depender de prefijos) y se bloquean DTD y entidades externas.
 */
public final class SupplierInvoiceParser {

    /** Tamaño máximo del archivo y de lo que se descomprime de un ZIP. */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private SupplierInvoiceParser() {
    }

    public record ParsedLine(int index, String code, String barcode, String description, BigDecimal quantity, String unitCode,
                             BigDecimal unitPrice, BigDecimal lineTotal, BigDecimal taxRate, BigDecimal tax) {
        /** La llave con la que se recuerda el producto para la próxima factura de este proveedor. */
        public String key() {
            String k = code != null ? code : barcode != null ? barcode : description;
            return k == null ? null : k.length() > 60 ? k.substring(0, 60) : k;
        }
    }

    public record ParsedInvoice(String number, String cufe, LocalDate issueDate, LocalDate dueDate, String currency,
                                String supplierTaxId, String supplierDv, String supplierName, String supplierEmail,
                                String supplierAddress, /** NATURAL o JURIDICA (null si no lo dice). */ String supplierPersonType,
                                String buyerTaxId, BigDecimal subtotal, BigDecimal taxTotal,
                                BigDecimal total, List<ParsedLine> lines, String invoiceXml) {
    }

    public static ParsedInvoice parse(byte[] data) {
        if (data == null || data.length == 0) throw ApiException.badRequest("El archivo está vacío");
        if (data.length > MAX_BYTES) throw ApiException.badRequest("El archivo pesa más de 5 MB: sube solo el XML o el ZIP de la factura");
        byte[] xml = isZip(data) ? xmlFromZip(data) : data;
        Element root = read(new String(stripBom(xml), StandardCharsets.UTF_8)).getDocumentElement();
        String invoiceXml = new String(stripBom(xml), StandardCharsets.UTF_8);
        if ("AttachedDocument".equals(root.getLocalName())) {
            // La factura viaja como texto dentro de cac:Attachment/cac:ExternalReference/cbc:Description.
            Element desc = path(root, "Attachment", "ExternalReference", "Description");
            if (desc == null || desc.getTextContent().isBlank()) throw ApiException.badRequest("El contenedor no trae la factura adentro");
            invoiceXml = desc.getTextContent().trim();
            root = read(invoiceXml).getDocumentElement();
        }
        if ("CreditNote".equals(root.getLocalName()) || "DebitNote".equals(root.getLocalName())) {
            throw ApiException.badRequest("Es una nota " + ("CreditNote".equals(root.getLocalName()) ? "crédito" : "débito")
                    + ", no una factura: regístrala como devolución o ajuste con el proveedor.");
        }
        if (!"Invoice".equals(root.getLocalName())) throw ApiException.badRequest("El XML no es una factura electrónica (UBL)");

        Element supplier = path(root, "AccountingSupplierParty", "Party");
        Element customer = path(root, "AccountingCustomerParty", "Party");
        Element supplierId = supplier == null ? null : firstNonNull(path(supplier, "PartyTaxScheme", "CompanyID"), path(supplier, "PartyLegalEntity", "CompanyID"));
        String supplierName = supplier == null ? null : firstText(path(supplier, "PartyTaxScheme", "RegistrationName"),
                path(supplier, "PartyLegalEntity", "RegistrationName"), path(supplier, "PartyName", "Name"));
        String address = supplier == null ? null : firstText(path(supplier, "PartyTaxScheme", "RegistrationAddress", "AddressLine", "Line"),
                path(supplier, "PhysicalLocation", "Address", "AddressLine", "Line"));
        String email = supplier == null ? null : text(path(supplier, "Contact", "ElectronicMail"));
        // AdditionalAccountID del proveedor: 1 = persona jurídica, 2 = persona natural.
        String kind = text(path(root, "AccountingSupplierParty", "AdditionalAccountID"));
        String personType = "1".equals(kind) ? "JURIDICA" : "2".equals(kind) ? "NATURAL" : null;
        String buyer = customer == null ? null : firstText(path(customer, "PartyTaxScheme", "CompanyID"), path(customer, "PartyLegalEntity", "CompanyID"));

        BigDecimal tax = BigDecimal.ZERO;
        for (Element t : children(root, "TaxTotal")) tax = tax.add(num(child(t, "TaxAmount")));
        Element totals = child(root, "LegalMonetaryTotal");

        List<ParsedLine> lines = new ArrayList<>();
        int i = 0;
        for (Element l : children(root, "InvoiceLine")) {
            Element qty = child(l, "InvoicedQuantity");
            BigDecimal lineTax = BigDecimal.ZERO;
            BigDecimal rate = null;
            for (Element t : children(l, "TaxTotal")) {
                lineTax = lineTax.add(num(child(t, "TaxAmount")));
                Element pct = path(t, "TaxSubtotal", "TaxCategory", "Percent");
                if (rate == null && pct != null) rate = num(pct);
            }
            lines.add(new ParsedLine(i++, text(path(l, "Item", "SellersItemIdentification", "ID")),
                    text(path(l, "Item", "StandardItemIdentification", "ID")), text(path(l, "Item", "Description")),
                    num(qty), qty == null ? null : blank(qty.getAttribute("unitCode")), num(path(l, "Price", "PriceAmount")),
                    num(child(l, "LineExtensionAmount")), rate == null ? BigDecimal.ZERO : rate, lineTax));
        }
        if (lines.isEmpty()) throw ApiException.badRequest("La factura no trae productos (líneas)");
        String number = text(child(root, "ID"));
        if (number == null) throw ApiException.badRequest("La factura no trae número");

        return new ParsedInvoice(number, text(child(root, "UUID")), date(child(root, "IssueDate")),
                date(firstNonNull(child(root, "DueDate"), path(root, "PaymentMeans", "PaymentDueDate"))),
                text(child(root, "DocumentCurrencyCode")), digits(text(supplierId)),
                supplierId == null ? null : blank(supplierId.getAttribute("schemeID")), supplierName, email, address, personType, digits(buyer),
                totals == null ? BigDecimal.ZERO : num(child(totals, "LineExtensionAmount")), tax,
                totals == null ? BigDecimal.ZERO : num(child(totals, "PayableAmount")), lines, invoiceXml);
    }

    // ------------------------------------------------------------------ XML

    private static Document read(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // Sin DTD ni entidades externas (XXE): una factura nunca las necesita.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw ApiException.badRequest("No se pudo leer el XML: el archivo está dañado o no es una factura electrónica");
        }
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    private static Element path(Element from, String... names) {
        Element e = from;
        for (String n : names) {
            e = child(e, n);
            if (e == null) return null;
        }
        return e;
    }

    private static Element firstNonNull(Element... es) {
        for (Element e : es) if (e != null) return e;
        return null;
    }

    private static String firstText(Element... es) {
        for (Element e : es) {
            String t = text(e);
            if (t != null) return t;
        }
        return null;
    }

    private static String text(Element e) {
        return e == null ? null : blank(e.getTextContent());
    }

    private static BigDecimal num(Element e) {
        String t = text(e);
        if (t == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("La factura tiene un valor que no es un número: «" + t + "»");
        }
    }

    private static LocalDate date(Element e) {
        String t = text(e);
        try {
            return t == null ? null : LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String digits(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    // ------------------------------------------------------------------ ZIP

    private static boolean isZip(byte[] d) {
        return d.length > 3 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    /** El primer .xml del ZIP (los correos de la DIAN traen el XML y el PDF), sin descomprimir más de 5 MB. */
    private static byte[] xmlFromZip(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".xml")) continue;
                return readLimited(zip);
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("El ZIP está dañado");
        }
        throw ApiException.badRequest("El ZIP no trae ningún archivo XML");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw ApiException.badRequest("El XML dentro del ZIP pesa más de 5 MB");
        }
        return out.toByteArray();
    }

    private static byte[] stripBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(b, 3, b.length);
        }
        return b;
    }
}
