package com.tiendaposs.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 11 — "Terminado cuando": se agenda una cita, sale el recordatorio, se atiende y se cobra (servicio + producto);
 * la comisión aparece en la liquidación y se paga como nómina. Una orden de servicio de un celular pasa de recibido a
 * entregado con repuestos y anticipo.
 */
class ServicesIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long corteId;     // servicio (producto)
    long shampoo;     // presentación de un producto de venta
    long shampooId;
    long ana;         // profesional
    long pantalla;    // presentación del repuesto
    long pantallaId;
    LocalDate tomorrow = LocalDate.now().plusDays(1);

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"SERVICIOS\",\"modules\":[\"SERVICIOS\",\"CUENTAS_ABIERTAS\"]}").expect(200);
        corteId = api.post("/api/products", owner, """
                {"name":"Corte de cabello","unit":"UNIDAD","trackStock":false,"cost":0,"initialStock":0,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":25000,"isBase":true}]}""").expect(201).json().get("id").asLong();
        api.put("/api/services/catalog/" + corteId, owner, "{\"minutes\":30}").expect(200);
        JsonNode sh = api.post("/api/products", owner, """
                {"name":"Shampoo","unit":"UNIDAD","cost":8000,"initialStock":10,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":20000,"isBase":true}]}""").expect(201).json();
        shampooId = sh.get("id").asLong();
        shampoo = sh.get("presentations").get(0).get("id").asLong();
        JsonNode pa = api.post("/api/products", owner, """
                {"name":"Pantalla Samsung A10","unit":"UNIDAD","cost":60000,"initialStock":3,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":90000,"isBase":true}]}""").expect(201).json();
        pantallaId = pa.get("id").asLong();
        pantalla = pa.get("presentations").get(0).get("id").asLong();
        StringBuilder hours = new StringBuilder();
        for (int d = 1; d <= 7; d++) hours.append(d == 1 ? "" : ",").append("{\"weekday\":%d,\"start\":\"08:00\",\"end\":\"18:00\"}".formatted(d));
        ana = api.post("/api/services/staff", owner, """
                {"name":"Ana Gómez","commissionServicePct":40,"commissionProductPct":10,"hours":[%s]}""".formatted(hours)).expect(200).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
    }

    @Test
    void appointmentFromBookingToCommission() throws Exception {
        String at = tomorrow + "T10:00:00";
        JsonNode cita = api.post("/api/services/appointments", owner, """
                {"staffId":%d,"serviceId":%d,"customerName":"María Pérez","customerPhone":"300 123 4567","start":"%s"}""".formatted(ana, corteId, at))
                .expect(200).json();
        long id = cita.get("id").asLong();
        assertThat(cita.get("minutes").asInt()).isEqualTo(30);
        assertThat(cita.get("price").decimalValue()).isEqualByComparingTo("25000");
        // Recordatorio por WhatsApp listo para enviar.
        assertThat(cita.get("whatsappUrl").asText()).startsWith("https://wa.me/573001234567?text=").contains("Corte%20de%20cabello");

        // Ana no puede tener otra cita que se cruce.
        api.post("/api/services/appointments", owner, """
                {"staffId":%d,"serviceId":%d,"customerName":"Otro","start":"%sT10:15:00"}""".formatted(ana, corteId, tomorrow)).expect(409);
        JsonNode slots = api.get("/api/services/slots?serviceId=%d&date=%s".formatted(corteId, tomorrow), owner).expect(200).json();
        assertThat(slots.toString()).doesNotContain("\"10:00\"").contains("\"10:30\"");

        api.post("/api/services/appointments/" + id + "/reminder", owner, null).expect(200);
        JsonNode agenda = api.get("/api/services/agenda?date=" + tomorrow, owner).expect(200).json();
        assertThat(agenda.get("appointments").get(0).get("reminderSentAt").isNull()).isFalse();

        // Se atiende y se pasa a la caja: el cliente lleva además un shampoo.
        JsonNode acc = api.post("/api/services/appointments/" + id + "/charge", owner, null).expect(200).json();
        long account = acc.get("id").asLong();
        assertThat(acc.get("total").decimalValue()).isEqualByComparingTo("25000");
        api.post("/api/pos/accounts/" + account + "/lines", owner, "{\"line\":{\"presentationId\":%d,\"quantity\":1}}".formatted(shampoo)).expect(200);
        JsonNode sale = api.post("/api/pos/accounts/" + account + "/checkout", owner,
                "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":50000}]}").expect(200).json();
        assertThat(sale.get("total").decimalValue()).isEqualByComparingTo("45000");
        assertThat(api.get("/api/services/agenda?date=" + tomorrow, owner).json().get("appointments").get(0).get("saleStatus").asText()).isEqualTo("PAGADA");

        // Comisión: 40 % del corte + 10 % del shampoo = 10.000 + 2.000.
        LocalDate today = LocalDate.now();
        String q = "?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        JsonNode rep = api.get("/api/services/commissions" + q, owner).expect(200).json();
        JsonNode row = rep.get("staff").get(0);
        assertThat(row.get("serviceCommission").decimalValue()).isEqualByComparingTo("10000");
        assertThat(row.get("productCommission").decimalValue()).isEqualByComparingTo("2000");
        assertThat(row.get("total").decimalValue()).isEqualByComparingTo("12000");

        // Se liquida como gasto de nómina; no se puede pagar dos veces.
        String pay = "{\"staffId\":%d,\"from\":\"%s\",\"to\":\"%s\",\"paidFrom\":\"BANCO\"}".formatted(ana, today.minusDays(1), today.plusDays(1));
        JsonNode paid = api.post("/api/services/commissions/pay", owner, pay).expect(200).json();
        assertThat(paid.get("staff").get(0).get("paid").decimalValue()).isEqualByComparingTo("12000");
        api.post("/api/services/commissions/pay", owner, pay).expect(409);
        JsonNode entries = api.get("/api/finance/entries?kind=GASTO", owner).expect(200).json();
        assertThat(entries.toString()).contains("Comisiones de Ana Gómez").contains("Nómina");
    }

    @Test
    void phoneRepairFromReceivedToDelivered() throws Exception {
        JsonNode o = api.post("/api/service-orders", owner, """
                {"customerName":"Carlos Ruiz","customerPhone":"3109876543","device":"Celular","brand":"Samsung","model":"A10",
                 "problem":"Pantalla rota, no da imagen","accessories":"Sin cargador","technicianId":%d,"warrantyDays":60}""".formatted(ana))
                .expect(200).json();
        long id = o.get("id").asLong();
        assertThat(o.get("status").asText()).isEqualTo("RECIBIDO");
        assertThat(o.get("number").asLong()).isEqualTo(1);

        // Diagnóstico y cotización: pantalla + mano de obra.
        api.post("/api/service-orders/" + id + "/status", owner, "{\"status\":\"ESPERANDO_APROBACION\"}").expect(400); // falta el diagnóstico
        api.put("/api/service-orders/" + id, owner, """
                {"customerName":"Carlos Ruiz","customerPhone":"3109876543","device":"Celular","brand":"Samsung","model":"A10",
                 "problem":"Pantalla rota, no da imagen","diagnosis":"Cambio de pantalla","laborPrice":30000,"technicianId":%d,"warrantyDays":60}""".formatted(ana))
                .expect(200);
        api.post("/api/service-orders/" + id + "/parts", owner, "{\"presentationId\":%d,\"quantity\":1}".formatted(pantalla)).expect(200);
        o = api.post("/api/service-orders/" + id + "/status", owner, "{\"status\":\"ESPERANDO_APROBACION\"}").expect(200).json();
        assertThat(o.get("total").decimalValue()).isEqualByComparingTo("120000");
        assertThat(o.get("whatsappUrl").asText()).contains("573109876543");

        // Aprueba y deja 50.000 de anticipo en efectivo (entra hoy a la caja).
        o = api.post("/api/service-orders/" + id + "/quote", owner, "{\"approved\":true}").expect(200).json();
        assertThat(o.get("status").asText()).isEqualTo("EN_REPARACION");
        o = api.post("/api/service-orders/" + id + "/deposits", owner, "{\"amount\":50000,\"method\":\"EFECTIVO\"}").expect(200).json();
        assertThat(o.get("balance").decimalValue()).isEqualByComparingTo("70000");
        JsonNode shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("100000"); // 50.000 de base + anticipo

        // No se entrega sin estar listo.
        api.post("/api/service-orders/" + id + "/deliver", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":70000}]}").expect(409);
        api.post("/api/service-orders/" + id + "/status", owner, "{\"status\":\"LISTO\"}").expect(200);
        JsonNode res = api.post("/api/service-orders/" + id + "/deliver", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":70000}]}")
                .expect(200).json();
        assertThat(res.get("order").get("status").asText()).isEqualTo("ENTREGADO");
        assertThat(res.get("order").get("warrantyUntil").asText()).isEqualTo(LocalDate.now().plusDays(60).toString());
        assertThat(res.get("sale").get("total").decimalValue()).isEqualByComparingTo("120000");

        // La pantalla salió del inventario; la caja tiene la base + anticipo + saldo (el anticipo no se cuenta dos veces).
        assertThat(api.get("/api/products/" + pantallaId, owner).json().get("stock").decimalValue()).isEqualByComparingTo("2");
        shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("170000");
        // Dinero que entró hoy: anticipo + saldo = 120.000 (no 170.000).
        JsonNode dash = api.get("/api/dashboard", owner).expect(200).json();
        assertThat(dash.get("periods").get(0).get("moneyIn").decimalValue()).isEqualByComparingTo("120000");

        // La mano de obra cuenta como servicio de Ana (40 % de 30.000); la pantalla como producto (10 % de 90.000: el taller decide si el técnico gana por repuestos).
        LocalDate today = LocalDate.now();
        JsonNode rep = api.get("/api/services/commissions?from=%s&to=%s".formatted(today.minusDays(1), today.plusDays(1)), owner).json();
        assertThat(rep.get("staff").get(0).get("serviceCommission").decimalValue()).isEqualByComparingTo("12000");
        assertThat(rep.get("staff").get(0).get("productCommission").decimalValue()).isEqualByComparingTo("9000");
    }

    @Test
    void moduleRequired() throws Exception {
        String other = newStore().ownerToken();
        api.get("/api/services/agenda", other).expect(409);
        api.get("/api/service-orders", other).expect(409);
    }
}
