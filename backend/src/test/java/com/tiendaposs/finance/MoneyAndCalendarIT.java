package com.tiendaposs.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 15: cuentas con saldo, flujo proyectado, calendario tributario y tablero financiero. */
class MoneyAndCalendarIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long pres;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        pres = api.post("/api/products", owner, """
                {"name":"Cerveza","unit":"UNIDAD","cost":2500,"initialStock":500,
                 "presentations":[{"name":"Unidad","factor":1,"price":4000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
    }

    private JsonNode sell(int qty, String method, String reference) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"%s","amount":%d%s}]}"""
                .formatted(UUID.randomUUID(), pres, qty, method, qty * 4000, reference == null ? "" : ",\"reference\":\"" + reference + "\"")).expect(201).json();
    }

    private JsonNode account(String name, String kind, String methods, int opening) throws Exception {
        return api.post("/api/finance/accounts", owner, """
                {"name":"%s","kind":"%s","methods":[%s],"openingBalance":%d,"openingDate":"%s"}"""
                .formatted(name, kind, methods, opening, today.minusDays(1))).expect(201).json();
    }

    private JsonNode find(JsonNode overview, long id) {
        for (JsonNode a : overview.get("accounts")) if (a.get("id").asLong() == id) return a;
        throw new AssertionError("cuenta " + id + " no está");
    }

    @Test
    void accountsAddUpWhatTheSystemAlreadyRecords() throws Exception {
        long bank = account("Bancolombia ahorros", "BANCO", "\"TRANSFERENCIA\",\"TARJETA\"", 100000).get("id").asLong();
        long nequi = account("Nequi del negocio", "BILLETERA", "\"NEQUI\"", 0).get("id").asLong();
        // Un medio va a una sola cuenta, y el efectivo no va en cuentas.
        api.post("/api/finance/accounts", owner, "{\"name\":\"Otra\",\"kind\":\"BANCO\",\"methods\":[\"TARJETA\"]}").expect(400);
        api.post("/api/finance/accounts", owner, "{\"name\":\"Caja fuerte\",\"kind\":\"OTRA\",\"methods\":[\"EFECTIVO\"]}").expect(400);

        // Transferencia: queda por verificar hasta confirmarla; el datáfono nace confirmado.
        JsonNode transfer = sell(5, "TRANSFERENCIA", "T-991");
        sell(2, "TARJETA", null);
        sell(3, "EFECTIVO", null); // el efectivo es de la caja: no mueve cuentas
        JsonNode ov = api.get("/api/finance/accounts", owner).expect(200).json();
        assertThat(find(ov, bank).get("balance").decimalValue()).isEqualByComparingTo("108000");
        assertThat(find(ov, bank).get("pendingVerification").decimalValue()).isEqualByComparingTo("20000");
        api.post("/api/payments/" + transfer.get("payments").get(0).get("id").asLong() + "/confirm", owner, null).expect(200);

        // Gasto pagado desde el banco por transferencia y otro desde la caja.
        api.post("/api/finance/entries", owner, """
                {"kind":"GASTO","description":"Internet","amount":9000,"method":"TRANSFERENCIA","paidFrom":"BANCO"}""").expect(200);
        api.post("/api/finance/entries", owner, """
                {"kind":"GASTO","description":"Bolsas","amount":3000,"method":"EFECTIVO","paidFrom":"CAJA"}""").expect(200);
        // Comisión, traslado a Nequi y un ajuste (que exige explicación).
        api.post("/api/finance/account-moves", owner, "{\"accountId\":%d,\"type\":\"COMISION\",\"amount\":1500,\"note\":\"Cuota de manejo\"}".formatted(bank)).expect(200);
        api.post("/api/finance/account-moves", owner, "{\"accountId\":%d,\"type\":\"TRASLADO\",\"amount\":20000,\"toAccountId\":%d}".formatted(bank, nequi)).expect(200);
        api.post("/api/finance/account-moves", owner, "{\"accountId\":%d,\"type\":\"AJUSTE\",\"amount\":-500}".formatted(bank)).expect(400);
        JsonNode adj = api.post("/api/finance/account-moves", owner,
                "{\"accountId\":%d,\"type\":\"AJUSTE\",\"amount\":-500,\"note\":\"Diferencia con el extracto\"}".formatted(bank)).expect(200).json();

        // 100.000 + 20.000 + 8.000 − 9.000 − 1.500 − 20.000 − 500 = 97.000
        ov = api.get("/api/finance/accounts", owner).json();
        assertThat(find(ov, bank).get("balance").decimalValue()).isEqualByComparingTo("97000");
        assertThat(find(ov, nequi).get("balance").decimalValue()).isEqualByComparingTo("20000");
        assertThat(ov.get("total").decimalValue()).isEqualByComparingTo("117000");
        assertThat(adj.get("closingBalance").decimalValue()).isEqualByComparingTo("97000");
        assertThat(adj.get("lines").toString()).contains("Venta #", "Gasto: Internet", "Traslado a Nequi del negocio", "Comisión");

        // Anular el ajuste lo devuelve.
        long adjId = 0;
        for (JsonNode l : adj.get("lines")) if (l.get("concept").asText().startsWith("Ajuste")) adjId = l.get("refId").asLong();
        api.post("/api/finance/account-moves/" + adjId + "/void", owner, "{\"reason\":\"era otro mes\"}").expect(204);
        assertThat(find(api.get("/api/finance/accounts", owner).json(), bank).get("balance").decimalValue()).isEqualByComparingTo("97500");

        // El libro de Nequi muestra el traslado de entrada.
        JsonNode ledger = api.get("/api/finance/accounts/" + nequi + "/ledger?from=" + today.minusDays(3) + "&to=" + today, owner).expect(200).json();
        assertThat(ledger.get("lines").get(0).get("concept").asText()).startsWith("Traslado desde Bancolombia");

        // Otra tienda no ve estas cuentas; el cajero no las ve.
        api.get("/api/finance/accounts/" + bank + "/ledger", newStore().ownerToken()).expect(404);
        api.get("/api/finance/accounts", newEmployee(store, "CAJERO")).expect(403);
    }

    @Test
    void theProjectionStartsFromTodayAndPlacesEachDebtInItsWeek() throws Exception {
        account("Banco", "BANCO", "\"TRANSFERENCIA\"", 200000);
        sell(7, "EFECTIVO", null); // 28.000 en ventas de los últimos 28 días = 1.000 al día
        long sup = api.post("/api/suppliers", owner, "{\"name\":\"Bavaria\",\"creditDays\":10}").expect(201).json().get("id").asLong();
        long pid = api.get("/api/products?q=Cerveza", owner).json().get("items").get(0).get("id").asLong();
        api.post("/api/receipts", owner, "{\"supplierId\":%d,\"lines\":[{\"productId\":%d,\"unitsReceived\":40,\"unitCost\":2500}]}".formatted(sup, pid)).expect(201);

        JsonNode p = api.get("/api/finance/projection?weeks=4", owner).expect(200).json();
        assertThat(p.get("cash").decimalValue()).isEqualByComparingTo("78000");     // 50.000 de base + 28.000
        assertThat(p.get("accounts").decimalValue()).isEqualByComparingTo("200000");
        assertThat(p.get("start").decimalValue()).isEqualByComparingTo("278000");
        assertThat(p.get("avgDailySales").decimalValue()).isEqualByComparingTo("1000");
        assertThat(p.get("weeks")).hasSize(4);
        // La factura de 100.000 vence a los 10 días: segunda semana.
        assertThat(p.get("weeks").get(1).get("payables").decimalValue()).isEqualByComparingTo("100000");
        assertThat(p.get("weeks").get(0).get("payables").decimalValue()).isEqualByComparingTo("0");
        JsonNode w1 = p.get("weeks").get(0);
        assertThat(w1.get("closing").decimalValue()).isEqualByComparingTo(w1.get("opening").decimalValue().add(w1.get("sales").decimalValue())
                .add(w1.get("receivables").decimalValue()).subtract(w1.get("payables").decimalValue())
                .subtract(w1.get("fixedExpenses").decimalValue()).subtract(w1.get("restock").decimalValue()));
        assertThat(p.get("assumptions").toString()).contains("28 días");
    }

    @Test
    void theTaxCalendarIsAReminderBuiltFromTheFiscalProfile() throws Exception {
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com"}""").expect(200);
        JsonNode cal = api.get("/api/finance/tax-calendar", owner).expect(200).json();
        assertThat(cal.get("settings").get("configured").asBoolean()).isFalse();
        assertThat(cal.get("settings").get("ivaPeriod").asText()).isEqualTo("CUATRIMESTRAL");
        assertThat(cal.get("settings").get("retentionAgent").asBoolean()).isTrue();
        assertThat(cal.get("settings").get("incomeTax").asText()).isEqualTo("JURIDICA");
        assertThat(cal.get("items")).isEmpty(); // nada hasta que se confirme

        cal = api.put("/api/finance/tax-calendar/settings", owner, """
                {"ivaPeriod":"CUATRIMESTRAL","simpleRegime":false,"retentionAgent":true,"icaPeriod":"NINGUNO","incomeTax":"JURIDICA",
                 "mercantileRegistry":true,"reminderDays":7}""").expect(200).json();
        JsonNode items = cal.get("items");
        long retentions = 0, iva = 0;
        for (JsonNode i : items) {
            if (i.get("code").asText().equals("RETENCION")) retentions++;
            if (i.get("code").asText().equals("IVA")) {
                iva++;
                assertThat(i.get("estimated").asBoolean()).isTrue();
                assertThat(i.get("name").asText()).contains("cuatrimestral");
            }
            if (i.get("code").asText().equals("MATRICULA")) {
                assertThat(i.get("estimated").asBoolean()).isFalse();
                assertThat(i.get("dueDate").asText()).endsWith("-03-31");
            }
        }
        assertThat(retentions).isBetween(11L, 12L); // desde hoy hasta 12 meses
        assertThat(iva).isBetween(2L, 3L);

        // El contador pone la fecha exacta y se marca hecha.
        long first = items.get(0).get("id").asLong();
        JsonNode upd = api.put("/api/finance/tax-calendar/items/" + first, owner, "{\"dueDate\":\"%s\"}".formatted(today.plusDays(2))).expect(200).json();
        assertThat(upd.get("estimated").asBoolean()).isFalse();
        assertThat(upd.get("status").asText()).isEqualTo("PROXIMA");
        assertThat(api.get("/api/alerts", owner).json().findValuesAsText("type")).contains("OBLIGACION_PROXIMA");
        upd = api.put("/api/finance/tax-calendar/items/" + first, owner, "{\"done\":true}").expect(200).json();
        assertThat(upd.get("status").asText()).isEqualTo("HECHA");
        for (JsonNode a : api.get("/api/alerts", owner).json()) assertThat(a.path("referenceId").asLong()).isNotEqualTo(first);

        // Recordatorio propio, vencido.
        JsonNode own = api.post("/api/finance/tax-calendar/items", owner, "{\"name\":\"Pagar el predial\",\"dueDate\":\"%s\"}".formatted(today.minusDays(1)))
                .expect(201).json();
        assertThat(own.get("status").asText()).isEqualTo("VENCIDA");
        assertThat(api.get("/api/alerts", owner).json().findValuesAsText("type")).contains("OBLIGACION_VENCIDA");
        api.call("DELETE", "/api/finance/tax-calendar/items/" + own.get("id").asLong(), owner, null).expect(204);
        api.call("DELETE", "/api/finance/tax-calendar/items/" + items.get(1).get("id").asLong(), owner, null).expect(400);

        // Pasar a IVA bimestral rehace las fechas estimadas.
        cal = api.put("/api/finance/tax-calendar/settings", owner, """
                {"ivaPeriod":"BIMESTRAL","simpleRegime":false,"retentionAgent":false,"icaPeriod":"BIMESTRAL","incomeTax":"JURIDICA",
                 "mercantileRegistry":true,"reminderDays":7}""").expect(200).json();
        String all = cal.get("items").toString();
        assertThat(all).contains("IVA (bimestral)", "ICA (bimestral)");
        for (JsonNode i : cal.get("items")) {
            if (i.get("code").asText().equals("IVA") && i.path("doneAt").isMissingNode()) assertThat(i.get("name").asText()).contains("bimestral");
        }
    }

    @Test
    void theDashboardBringsEverythingTogether() throws Exception {
        account("Banco", "BANCO", "\"TRANSFERENCIA\",\"TARJETA\"", 50000);
        sell(2, "TARJETA", null);
        sell(1, "NEQUI", "N-1");
        JsonNode d = api.get("/api/finance/dashboard", owner).expect(200).json();
        assertThat(d.get("results").get("sales").decimalValue()).isEqualByComparingTo("12000");
        assertThat(d.get("money").get("inAccounts").decimalValue()).isEqualByComparingTo("58000");
        assertThat(d.get("money").get("accounts")).hasSize(1);
        assertThat(d.get("reconciliation").get("pendingCount").asLong()).isEqualTo(1);
        assertThat(d.get("documents").get("enabled").asBoolean()).isFalse();
        assertThat(d.get("outlook").get("closings")).hasSize(8);
        assertThat(d.get("alerts").findValuesAsText("type")).contains("PAGOS_POR_VERIFICAR");
        api.get("/api/finance/dashboard", newEmployee(store, "CAJERO")).expect(403);
    }
}
