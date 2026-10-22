package com.tiendaposs.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 16F: empleados, liquidación del mes, aprobación con comprobante contable y nómina electrónica (simulada). */
class PayrollIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    private String employee(String doc, String first, String salary) {
        return """
                {"docType":"CC","docNumber":"%s","firstName":"%s","firstSurname":"Gómez","contractType":"2","salary":%s,"arlClass":1,
                 "startDate":"2024-01-10","address":"Calle 5 # 1-2","municipalityCode":"11001","paymentMethod":"10"}""".formatted(doc, first, salary);
    }

    @Test
    void aMonthIsLiquidatedApprovedBookedAndTransmitted() throws Exception {
        // Sin los valores del año no se liquida.
        api.post("/api/payroll/employees", owner, employee("1010", "Laura", "1423500")).expect(201);
        api.post("/api/payroll/periods", owner, "{\"year\":%d,\"month\":%d}".formatted(today.getYear(), today.getMonthValue())).expect(400);
        api.put("/api/payroll/parameters", owner, """
                {"year":%d,"minimumWage":1423500,"transportAid":200000,"monthlyHours":220,"sundaySurchargePct":80,"exonerated":false}""".formatted(today.getYear())).expect(200);
        api.post("/api/payroll/employees", owner, employee("1010", "Otra", "1423500")).expect(400); // documento repetido
        long luis = api.post("/api/payroll/employees", owner, employee("2020", "Luis", "2000000")).expect(201).json().get("id").asLong();

        JsonNode period = api.post("/api/payroll/periods", owner, "{\"year\":%d,\"month\":%d}".formatted(today.getYear(), today.getMonthValue())).expect(200).json();
        long pid = period.get("period").get("id").asLong();
        assertThat(period.get("payslips").size()).isEqualTo(2);
        api.post("/api/payroll/periods", owner, "{\"year\":%d,\"month\":%d}".formatted(today.getYear(), today.getMonthValue())).expect(400);

        JsonNode withOt = api.post("/api/payroll/periods/" + pid + "/novelties", owner,
                "{\"employeeId\":%d,\"kind\":\"HORA\",\"code\":\"1\",\"quantity\":10}".formatted(luis)).expect(200).json();
        JsonNode slip = null;
        for (JsonNode s : withOt.get("payslips")) if (s.get("employeeId").asLong() == luis) slip = s;
        assertThat(slip.get("detail").toString()).contains("\"hora\"");
        api.post("/api/payroll/periods/" + pid + "/novelties", owner, "{\"employeeId\":%d,\"kind\":\"HORA\",\"quantity\":2}".formatted(luis)).expect(400);

        api.post("/api/accounting/enable", owner, "{\"enabled\":true}").expect(200);
        JsonNode approved = api.post("/api/payroll/periods/" + pid + "/approve", owner, "{\"paymentDate\":\"" + today + "\"}").expect(200).json();
        assertThat(approved.get("period").get("status").asText()).isEqualTo("APROBADA");
        api.post("/api/payroll/periods/" + pid + "/novelties", owner, "{\"employeeId\":%d,\"kind\":\"COMISION\",\"amount\":1000}".formatted(luis)).expect(400);

        // Nómina electrónica: necesita datos fiscales del negocio; con el simulador queda aceptada.
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        JsonNode sent = api.post("/api/payroll/periods/" + pid + "/transmit", owner, null).expect(200).json();
        for (JsonNode s : sent.get("payslips")) {
            assertThat(s.get("document").get("documentType").asText()).isEqualTo("NOMINA");
            assertThat(s.get("document").get("status").asText()).isEqualTo("ACEPTADO");
        }
        // Transmitir otra vez no duplica.
        api.post("/api/payroll/periods/" + pid + "/transmit", owner, null).expect(200);
        long slipId = sent.get("payslips").get(0).get("id").asLong();
        JsonNode deleted = api.post("/api/payroll/payslips/" + slipId + "/delete-electronic", owner, "{\"reason\":\"Error en el salario\"}").expect(200).json();
        assertThat(deleted.get("payslips").get(0).get("deletedBeforeDian").asBoolean()).isTrue();
        api.post("/api/payroll/payslips/" + slipId + "/delete-electronic", owner, null).expect(400);

        // Comprobante contable de la nómina (queda pendiente si faltan las cuentas 2505/2370/2610 en el plan).
        JsonNode pending = api.get("/api/accounting/pending", owner).json();
        JsonNode journal = api.get("/api/accounting/journal", owner).json();
        assertThat(journal.toString() + pending.toString()).contains("PAYROLL");

        // Un cajero no ve la nómina.
        String cashier = newEmployee(store, "CAJERO");
        api.get("/api/payroll/employees", cashier).expect(403);
    }
}
