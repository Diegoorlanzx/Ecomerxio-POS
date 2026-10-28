package com.tiendaposs.pharmacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Droguería: venta por unidad/blíster/caja, fórmula médica obligatoria, libro de fórmulas, lotes al recibir y nunca vender vencidos. */
class PharmacyIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    Api api;
    String owner;
    long supplier;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        String email = unique("drogueria") + "@prueba.com";
        owner = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"Droguería La Salud","ownerName":"Dueña","email":"%s","password":"Clave1234","businessType":"DROGUERIA",
                 "acceptTerms":true,"acceptPrivacy":true}""".formatted(email))).andExpect(status().isCreated())).get("token").asText();
        supplier = api.post("/api/suppliers", owner, "{\"name\":\"Laboratorio\",\"whatsapp\":\"3001234567\"}").expect(201).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    private JsonNode medicine(String name) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":300,"trackLots":true,
                 "presentations":[{"name":"Tableta","factor":1,"price":500,"isBase":true},
                                  {"name":"Blíster x10","factor":10,"price":4500,"isBase":false},
                                  {"name":"Caja x30","factor":30,"price":12000,"isBase":false}]}""".formatted(name)).expect(201).json();
    }

    private void receive(long productId, int units, String lot, LocalDate expiry) throws Exception {
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":%d,"unitCost":300,"lotCode":"%s","expiryDate":"%s"}]}"""
                .formatted(supplier, productId, units, lot, expiry)).expect(201);
    }

    private long pres(JsonNode p, String name) {
        for (JsonNode x : p.get("presentations")) if (x.get("name").asText().equals(name)) return x.get("id").asLong();
        throw new IllegalStateException(name);
    }

    private Api.Res sell(long presentationId, int qty, String prescription) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"EFECTIVO","amount":99999999}]%s}
                """.formatted(UUID.randomUUID(), presentationId, qty, prescription == null ? "" : ",\"prescription\":" + prescription));
    }

    @Test
    void ventaFraccionadaConFormulaYLibro() throws Exception {
        assertThat(api.get("/api/auth/me", owner).json().get("modules").toString()).contains("DROGUERIA", "LOTES");
        JsonNode amox = medicine("Amoxicilina 500 mg");
        long id = amox.get("id").asLong();
        api.put("/api/products/" + id + "/pharmacy", owner, """
                {"invimaCode":"INVIMA 2019M-0012345","activeIngredient":"Amoxicilina","requiresPrescription":true,"controlled":false}""").expect(200);

        // Al recibir en droguería el lote y el vencimiento son obligatorios.
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":30,"unitCost":300}]}""".formatted(supplier, id)).expect(400);
        receive(id, 60, "AMX-01", LocalDate.now().plusMonths(18));

        // Sin fórmula no se vende; con fórmula sí, y queda en el libro.
        sell(pres(amox, "Blíster x10"), 1, null).expect(400);
        long saleId = sell(pres(amox, "Blíster x10"), 1, """
                {"doctorName":"Dra. Ana Gómez","doctorLicense":"RM 12345","prescriptionNumber":"F-778","prescriptionDate":"%s","patientName":"Luis Pérez","patientDoc":"1010"}"""
                .formatted(LocalDate.now())).expect(201).json().get("id").asLong();
        sell(pres(amox, "Tableta"), 3, """
                {"doctorName":"Dr. B","doctorLicense":"RM 9","prescriptionDate":"%s","patientName":"Ana"}""".formatted(LocalDate.now())).expect(201);
        assertThat(api.get("/api/products/" + id, owner).json().get("stock").decimalValue()).isEqualByComparingTo("47");

        JsonNode book = api.get("/api/pharmacy/prescriptions?from=%s&to=%s".formatted(LocalDate.now(), LocalDate.now()), owner).expect(200).json();
        assertThat(book).hasSize(2);
        assertThat(book.toString()).contains("Dra. Ana Gómez", "RM 12345", "Amoxicilina 500 mg (Blíster x10)");
        assertThat(book.findValuesAsText("saleId")).contains(String.valueOf(saleId));

        JsonNode pos = api.get("/api/pos/catalog", owner).json();
        assertThat(pos.toString()).contains("\"activeIngredient\":\"Amoxicilina\"", "\"requiresPrescription\":true");
    }

    @Test
    void nuncaSeVendenVencidos() throws Exception {
        JsonNode acet = medicine("Acetaminofén 500 mg");
        long id = acet.get("id").asLong();
        receive(id, 10, "VIEJO", LocalDate.now().plusDays(10));
        receive(id, 5, "NUEVO", LocalDate.now().plusYears(2));
        // El lote VIEJO se vence (no se puede recibir ya vencido: se simula el paso del tiempo).
        jdbc.update("UPDATE product_lots SET expiry_date = ? WHERE product_id = ? AND lot_code = 'VIEJO'", LocalDate.now().minusDays(1), id);

        // Hay 15 en existencia pero solo 5 vigentes: 6 no se pueden vender; 5 sí, y salen del lote vigente.
        sell(pres(acet, "Tableta"), 6, null).expect(400);
        sell(pres(acet, "Tableta"), 5, null).expect(201);
        Integer viejo = jdbc.queryForObject("SELECT remaining::int FROM product_lots WHERE product_id = ? AND lot_code = 'VIEJO'", Integer.class, id);
        Integer nuevo = jdbc.queryForObject("SELECT remaining::int FROM product_lots WHERE product_id = ? AND lot_code = 'NUEVO'", Integer.class, id);
        assertThat(viejo).isEqualTo(10);
        assertThat(nuevo).isZero();
        sell(pres(acet, "Tableta"), 1, null).expect(400);
    }
}
