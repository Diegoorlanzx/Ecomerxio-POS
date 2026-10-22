package com.tiendaposs.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiendaposs.fiscal.Nit;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 13E: documento soporte desde la recepción e importación de facturas electrónicas de proveedores. */
class SupplierDocumentsIT extends AbstractIntegrationTest {

    static final String SUPPLIER_NIT = "900123456";
    static final String SUPPLIER_DV = String.valueOf(Nit.dv(SUPPLIER_NIT));

    Api api;
    String owner;
    long gaseosa;   // producto con una caja x12 con código de barras
    long arroz;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        gaseosa = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,"initialStock":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true},
                                  {"name":"Caja x12","factor":12,"barcode":"7701234567890","price":33000}]}""").expect(201).json().get("id").asLong();
        arroz = api.post("/api/products", owner, """
                {"name":"Arroz","unit":"UNIDAD","cost":2000,"taxRate":0,"taxClass":"EXCLUIDO","initialStock":0,
                 "presentations":[{"name":"Libra","factor":1,"price":2500,"isBase":true}]}""").expect(201).json().get("id").asLong();
    }

    // ------------------------------------------------------------------ Documento soporte

    private void invoicingEnabled() throws Exception {
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        String[][] types = {{"POS_ELECTRONICO", "POS"}, {"FACTURA", "FE"}, {"DOCUMENTO_SOPORTE", "DS"}};
        for (int i = 0; i < types.length; i++) {
            api.post("/api/fiscal/resolutions", owner, """
                    {"documentType":"%s","resolutionNumber":"1876%d","prefix":"%s","rangeFrom":1,"rangeTo":1000,
                     "validFrom":"%s","validTo":"%s"}""".formatted(types[i][0], i, types[i][1], today.minusDays(5), today.plusYears(1))).expect(200);
        }
        api.post("/api/einvoice/test", owner, null).expect(200);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200);
    }

    private long supplier(String name, boolean invoices, boolean complete) throws Exception {
        String extra = complete ? """
                ,"taxId":"1020304050","docType":"CC","personType":"NATURAL","address":"Vereda El Rosal","municipalityCode":"25754"
                """ : "";
        return api.post("/api/suppliers", owner, """
                {"name":"%s","invoicesElectronically":%s%s}""".formatted(name, invoices, extra)).expect(201).json().get("id").asLong();
    }

    private JsonNode receive(long supplierId) throws Exception {
        return api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":50,"unitCost":1800}]}""".formatted(supplierId, arroz)).expect(201).json();
    }

    private JsonNode supportDocuments() throws Exception {
        ArrayNode out = json.createArrayNode();
        for (JsonNode d : api.get("/api/einvoice/documents", owner).json()) if ("DOCUMENTO_SOPORTE".equals(d.get("documentType").asText())) out.add(d);
        return out;
    }

    @Test
    void aPurchaseFromANonInvoicingSupplierIssuesASupportDocument() throws Exception {
        invoicingEnabled();
        long farmer = supplier("Don Pedro (campesino)", false, true);
        long company = supplier("Distribuidora SAS", true, false);

        JsonNode rec = receive(farmer);
        receive(company); // la empresa factura: no lleva documento soporte

        JsonNode ds = supportDocuments();
        assertThat(ds).hasSize(1);
        JsonNode d = ds.get(0);
        assertThat(d.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(d.get("fullNumber").asText()).isEqualTo("DS1");
        assertThat(d.get("total").decimalValue()).isEqualByComparingTo("90000");
        assertThat(d.get("customerName").asText()).isEqualTo("Don Pedro (campesino)");
        assertThat(d.get("receiptNumber").asLong()).isEqualTo(rec.get("number").asLong());
        assertThat(d.get("supplierId").asLong()).isEqualTo(farmer);

        // Una recepción tiene un solo documento soporte; y el proveedor que factura no lo admite.
        api.post("/api/einvoice/receipts/" + rec.get("id").asLong() + "/support-document", owner, null).expect(400);
    }

    @Test
    void missingSupplierDataBlocksTheSupportDocumentUntilFixed() throws Exception {
        invoicingEnabled();
        long farmer = supplier("Doña Rosa", false, false);
        JsonNode sup = api.get("/api/suppliers/" + farmer, owner).json();
        assertThat(sup.get("supportDocumentMissing").toString()).contains("número de documento", "municipio");

        receive(farmer);
        JsonNode d = supportDocuments().get(0);
        assertThat(d.get("status").asText()).isEqualTo("BLOQUEADO");
        assertThat(d.get("lastErrorCode").asText()).isEqualTo("VAL_PROVEEDOR");
        assertThat(d.get("help").get("fixTab").asText()).isEqualTo("proveedores");

        api.put("/api/suppliers/" + farmer, owner, """
                {"name":"Doña Rosa","invoicesElectronically":false,"taxId":"%s","dv":"%s","docType":"NIT","personType":"NATURAL",
                 "address":"Finca La Esperanza","municipalityCode":"25754"}""".formatted(SUPPLIER_NIT, SUPPLIER_DV)).expect(200);
        JsonNode retried = api.post("/api/einvoice/documents/" + d.get("id").asLong() + "/retry", owner, null).expect(200).json();
        assertThat(retried.get("status").asText()).isEqualTo("ACEPTADO");
        // El DV equivocado se rechaza al guardar el proveedor.
        api.put("/api/suppliers/" + farmer, owner, """
                {"name":"Doña Rosa","taxId":"%s","dv":"%s","docType":"NIT"}""".formatted(SUPPLIER_NIT, (Integer.parseInt(SUPPLIER_DV) + 1) % 10)).expect(400);
    }

    @Test
    void anEarlierReceiptGetsItsSupportDocumentOnRequest() throws Exception {
        long farmer = supplier("Don Pedro", false, true);
        long rec = receive(farmer).get("id").asLong(); // todavía sin facturación: no sale documento
        api.post("/api/einvoice/receipts/" + rec + "/support-document", owner, null).expect(400);
        invoicingEnabled();
        assertThat(supportDocuments()).isEmpty();
        JsonNode d = api.post("/api/einvoice/receipts/" + rec + "/support-document", owner, null).expect(200).json();
        assertThat(d.get("documentType").asText()).isEqualTo("DOCUMENTO_SOPORTE");
        assertThat(api.get("/api/einvoice/documents/" + d.get("id").asLong(), owner).json().get("document").get("status").asText()).isEqualTo("ACEPTADO");
    }

    // ------------------------------------------------------------------ Facturas de proveedores

    /** Una factura UBL 2.1 mínima: 2 cajas de gaseosa (con código de barras) y 10 libras de arroz (sin código). */
    static String invoiceXml(String number, String cufe, String buyerNit) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ID>%s</cbc:ID>
                  <cbc:UUID schemeName="CUFE-SHA384">%s</cbc:UUID>
                  <cbc:IssueDate>2026-10-20</cbc:IssueDate>
                  <cbc:DueDate>2026-11-19</cbc:DueDate>
                  <cbc:DocumentCurrencyCode>COP</cbc:DocumentCurrencyCode>
                  <cac:AccountingSupplierParty>
                    <cbc:AdditionalAccountID>1</cbc:AdditionalAccountID>
                    <cac:Party>
                      <cac:PartyTaxScheme>
                        <cbc:RegistrationName>Distribuidora del Norte SAS</cbc:RegistrationName>
                        <cbc:CompanyID schemeID="%s" schemeName="31">%s</cbc:CompanyID>
                        <cac:RegistrationAddress><cac:AddressLine><cbc:Line>Cra 7 # 10-20</cbc:Line></cac:AddressLine></cac:RegistrationAddress>
                      </cac:PartyTaxScheme>
                      <cac:Contact><cbc:ElectronicMail>ventas@norte.test</cbc:ElectronicMail></cac:Contact>
                    </cac:Party>
                  </cac:AccountingSupplierParty>
                  <cac:AccountingCustomerParty>
                    <cac:Party><cac:PartyTaxScheme><cbc:CompanyID schemeID="4" schemeName="31">%s</cbc:CompanyID></cac:PartyTaxScheme></cac:Party>
                  </cac:AccountingCustomerParty>
                  <cac:TaxTotal><cbc:TaxAmount currencyID="COP">7600.00</cbc:TaxAmount></cac:TaxTotal>
                  <cac:LegalMonetaryTotal>
                    <cbc:LineExtensionAmount currencyID="COP">60000.00</cbc:LineExtensionAmount>
                    <cbc:PayableAmount currencyID="COP">67600.00</cbc:PayableAmount>
                  </cac:LegalMonetaryTotal>
                  <cac:InvoiceLine>
                    <cbc:ID>1</cbc:ID>
                    <cbc:InvoicedQuantity unitCode="94">2</cbc:InvoicedQuantity>
                    <cbc:LineExtensionAmount currencyID="COP">40000.00</cbc:LineExtensionAmount>
                    <cac:TaxTotal><cbc:TaxAmount currencyID="COP">7600.00</cbc:TaxAmount>
                      <cac:TaxSubtotal><cac:TaxCategory><cbc:Percent>19.00</cbc:Percent></cac:TaxCategory></cac:TaxSubtotal></cac:TaxTotal>
                    <cac:Item><cbc:Description>GASEOSA 400ML CAJA X12</cbc:Description>
                      <cac:StandardItemIdentification><cbc:ID schemeID="010">7701234567890</cbc:ID></cac:StandardItemIdentification></cac:Item>
                    <cac:Price><cbc:PriceAmount currencyID="COP">20000.00</cbc:PriceAmount></cac:Price>
                  </cac:InvoiceLine>
                  <cac:InvoiceLine>
                    <cbc:ID>2</cbc:ID>
                    <cbc:InvoicedQuantity unitCode="LBR">10</cbc:InvoicedQuantity>
                    <cbc:LineExtensionAmount currencyID="COP">20000.00</cbc:LineExtensionAmount>
                    <cac:Item><cbc:Description>ARROZ BLANCO LIBRA</cbc:Description>
                      <cac:SellersItemIdentification><cbc:ID>AR-500</cbc:ID></cac:SellersItemIdentification></cac:Item>
                    <cac:Price><cbc:PriceAmount currencyID="COP">2000.00</cbc:PriceAmount></cac:Price>
                  </cac:InvoiceLine>
                </Invoice>
                """.formatted(number, cufe, SUPPLIER_DV, SUPPLIER_NIT, buyerNit).strip();
    }

    /** Como llega por correo: el AttachedDocument con la factura adentro (CDATA), en un ZIP con el PDF. */
    static String zipped(String invoice) throws Exception {
        String attached = """
                <?xml version="1.0" encoding="UTF-8"?>
                <AttachedDocument xmlns="urn:oasis:names:specification:ubl:schema:xsd:AttachedDocument-2"
                                  xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                                  xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ID>AD-1</cbc:ID>
                  <cac:Attachment><cac:ExternalReference><cbc:MimeCode>text/xml</cbc:MimeCode>
                    <cbc:Description><![CDATA[%s]]></cbc:Description></cac:ExternalReference></cac:Attachment>
                </AttachedDocument>""".formatted(invoice).strip();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("ad0900123456.pdf"));
            zip.write("%PDF-1.4 falso".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("ad0900123456.xml"));
            zip.write(attached.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static boolean absent(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode importBody(String content, JsonNode preview) {
        ObjectNode b = json.createObjectNode();
        b.put("fileName", "factura.zip");
        b.put("contentBase64", content);
        ArrayNode lines = b.putArray("lines");
        for (JsonNode l : preview.get("lines")) {
            ObjectNode x = lines.addObject();
            x.put("index", l.get("index").asInt());
            if (!absent(l.path("match"))) {
                x.put("productId", l.get("match").get("productId").asLong());
                x.put("packFactor", l.get("match").get("packFactor").decimalValue());
            }
        }
        return b;
    }

    private BigDecimal stock(long productId) throws Exception {
        return api.get("/api/products/" + productId, owner).json().get("stock").decimalValue();
    }

    @Test
    void anEmailedZipBecomesAReceiptWithItsPayableAndIsRememberedNextTime() throws Exception {
        long sup = api.post("/api/suppliers", owner, """
                {"name":"Distribuidora del Norte","taxId":"%s-%s","creditDays":8}""".formatted(SUPPLIER_NIT, SUPPLIER_DV)).expect(201).json().get("id").asLong();
        String content = zipped(invoiceXml("FV-1001", "a".repeat(96), "800197268"));

        JsonNode pre = api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("fileName", "factura.zip")
                .put("contentBase64", content)).expect(200).json();
        assertThat(pre.get("number").asText()).isEqualTo("FV-1001");
        assertThat(pre.get("supplier").get("id").asLong()).isEqualTo(sup);
        assertThat(pre.get("total").decimalValue()).isEqualByComparingTo("67600");
        assertThat(pre.get("costIncludesTax").asBoolean()).isTrue(); // sin datos fiscales: el IVA va al costo
        JsonNode l0 = pre.get("lines").get(0);
        assertThat(l0.get("match").get("productId").asLong()).isEqualTo(gaseosa);
        assertThat(l0.get("match").get("matchedBy").asText()).isEqualTo("CODIGO_BARRAS");
        assertThat(l0.get("match").get("packFactor").decimalValue()).isEqualByComparingTo("12");
        assertThat(absent(pre.get("lines").get(1).path("match"))).isTrue();

        // Sin elegir el arroz no se importa.
        api.post("/api/supplier-invoices", owner, importBody(content, pre).put("supplierId", sup)).expect(400);
        ObjectNode body = importBody(content, pre).put("supplierId", sup);
        ((ObjectNode) body.get("lines").get(1)).put("productId", arroz).put("packFactor", 1);
        JsonNode imp = api.post("/api/supplier-invoices", owner, body).expect(201).json();
        assertThat(imp.get("receiptNumber").asLong()).isPositive();

        // 2 cajas x12 = 24 gaseosas a (40.000 + 7.600 de IVA) / 24.
        assertThat(stock(gaseosa)).isEqualByComparingTo("24");
        assertThat(api.get("/api/products/" + gaseosa, owner).json().get("cost").decimalValue()).isEqualByComparingTo("1983.3333");
        assertThat(stock(arroz)).isEqualByComparingTo("10");
        // La cuenta por pagar queda por el total de la factura, con su vencimiento.
        JsonNode payable = api.get("/api/finance/payables?supplierId=" + sup, owner).json().get("invoices").get(0);
        assertThat(payable.get("total").decimalValue()).isEqualByComparingTo("67600");
        assertThat(payable.get("invoiceNumber").asText()).isEqualTo("FV-1001");
        assertThat(payable.get("dueDate").asText()).isEqualTo("2026-11-19");

        // La misma factura no entra dos veces.
        api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", content)).expect(400);
        api.post("/api/supplier-invoices", owner, body).expect(400);

        // La siguiente factura del proveedor ya reconoce el arroz por su código.
        JsonNode next = api.post("/api/supplier-invoices/preview", owner, json.createObjectNode()
                .put("contentBase64", b64(invoiceXml("FV-1002", "b".repeat(96), "800197268")))).expect(200).json();
        assertThat(next.get("lines").get(1).get("match").get("productId").asLong()).isEqualTo(arroz);
        assertThat(next.get("lines").get(1).get("match").get("matchedBy").asText()).isEqualTo("MEMORIA");

        JsonNode list = api.get("/api/supplier-invoices", owner).expect(200).json();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("supplierName").asText()).isEqualTo("Distribuidora del Norte");
        String xml = api.get("/api/supplier-invoices/" + list.get(0).get("id").asLong() + "/xml", owner).expect(200).raw();
        assertThat(xml).startsWith("<?xml").contains("FV-1001");
        api.get("/api/supplier-invoices/" + list.get(0).get("id").asLong() + "/xml", newStore().ownerToken()).expect(404);
    }

    @Test
    void anImportCanCompleteAnOrderAndCreateTheSupplier() throws Exception {
        String content = b64(invoiceXml("FV-77", "c".repeat(96), "999999999"));
        JsonNode pre = api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", content)).expect(200).json();
        assertThat(absent(pre.path("supplier"))).isTrue();

        // Sin proveedor no se puede: se crea con los datos de la factura.
        ObjectNode body = importBody(content, pre);
        ((ObjectNode) body.get("lines").get(1)).put("productId", arroz).put("packFactor", 1);
        api.post("/api/supplier-invoices", owner, body).expect(400);
        body.put("createSupplier", true);
        JsonNode imp = api.post("/api/supplier-invoices", owner, body).expect(201).json();
        JsonNode created = api.get("/api/suppliers/" + imp.get("supplierId").asLong(), owner).json();
        assertThat(created.get("name").asText()).isEqualTo("Distribuidora del Norte SAS");
        assertThat(created.get("taxId").asText()).isEqualTo(SUPPLIER_NIT);
        assertThat(created.get("dv").asText()).isEqualTo(SUPPLIER_DV);
        assertThat(created.get("personType").asText()).isEqualTo("JURIDICA");
        assertThat(created.get("email").asText()).isEqualTo("ventas@norte.test");

        // Con una orden abierta del proveedor, la factura la completa.
        long sup = created.get("id").asLong();
        long order = api.post("/api/purchases", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"packName":"Caja x12","packFactor":12,"packs":2,"costPerPack":20000},
                                          {"productId":%d,"packs":10,"costPerPack":2000}]}""".formatted(sup, gaseosa, arroz)).expect(201).json().get("id").asLong();
        api.post("/api/purchases/" + order + "/submit", owner, null).expect(200);
        String second = b64(invoiceXml("FV-78", "d".repeat(96), "999999999"));
        JsonNode pre2 = api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", second)).expect(200).json();
        assertThat(pre2.get("openOrders").get(0).get("id").asLong()).isEqualTo(order);
        JsonNode imp2 = api.post("/api/supplier-invoices", owner, importBody(second, pre2).put("supplierId", sup).put("orderId", order)).expect(201).json();
        assertThat(imp2.get("orderNumber").asLong()).isPositive();
        assertThat(api.get("/api/purchases/" + order, owner).json().get("status").asText()).isEqualTo("RECIBIDA");
    }

    @Test
    void unsafeOrWrongFilesAreRejected() throws Exception {
        String xxe = """
                <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"><ID>&e;</ID></Invoice>""".strip();
        api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", b64(xxe))).expect(400);
        String note = invoiceXml("NC-1", "e".repeat(96), "1").replace("<Invoice ", "<CreditNote ").replace("</Invoice>", "</CreditNote>");
        assertThat(api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", b64(note))).expect(400)
                .json().get("message").asText()).contains("nota crédito");
        api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", b64("no soy un xml"))).expect(400);
        // Un proveedor marcado como no obligado a facturar no puede traer factura electrónica.
        long farmer = api.post("/api/suppliers", owner, """
                {"name":"Don Pedro","taxId":"%s","invoicesElectronically":false}""".formatted(SUPPLIER_NIT)).expect(201).json().get("id").asLong();
        String content = b64(invoiceXml("FV-5", "f".repeat(96), "1"));
        JsonNode pre = api.post("/api/supplier-invoices/preview", owner, json.createObjectNode().put("contentBase64", content)).expect(200).json();
        assertThat(pre.get("warnings").toString()).contains("NO obligado");
        ObjectNode body = importBody(content, pre).put("supplierId", farmer);
        ((ObjectNode) body.get("lines").get(1)).put("skip", true);
        api.post("/api/supplier-invoices", owner, body).expect(400);
    }
}
