package com.tiendaposs.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fase 3: pagos digitales con verificación y conciliación, fiado, gastos, cuentas por pagar y finanzas. */
class PaymentsFinanceIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    // ---------------- ayudas ----------------

    long product(String name, int price, int cost, int stock) throws Exception {
        JsonNode p = api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":%d,"initialStock":%d,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}
                """.formatted(name, cost, stock, price)).expect(201).json();
        return p.get("presentations").get(0).get("id").asLong();
    }

    JsonNode openShift(String token, int cash) throws Exception {
        long reg = api.get("/api/registers", token).expect(200).json().get(0).get("id").asLong();
        return api.post("/api/shifts/open", token, "{\"registerId\":%d,\"openingCash\":%d}".formatted(reg, cash)).expect(200).json();
    }

    Api.Res sell(String token, long pres, int qty, String payments, String extra) throws Exception {
        return api.post("/api/pos/sales", token, "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":%d}],\"payments\":[%s]%s}"
                .formatted(UUID.randomUUID(), pres, qty, payments, extra == null ? "" : "," + extra));
    }

    JsonNode shift(String token) throws Exception {
        return api.get("/api/shifts/current", token).expect(200).json();
    }

    long customer(String name, String doc, String phone) throws Exception {
        return api.post("/api/pos/customers", owner, "{\"name\":\"%s\",\"docNumber\":\"%s\",\"phone\":\"%s\"}".formatted(name, doc, phone))
                .expect(201).json().get("id").asLong();
    }

    void enableCredit(int limit, int days) throws Exception {
        api.put("/api/settings/pos/finance", owner, "{\"verifyDigitalPayments\":true,\"creditEnabled\":true,\"defaultCreditLimit\":%d,\"defaultCreditDays\":%d}"
                .formatted(limit, days)).expect(200);
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------- pagos digitales ----------------

    @Test
    void digitalPaymentsStayPendingUntilVerified() throws Exception {
        long pres = product("Cerveza", 4000, 2500, 50);
        openShift(owner, 50000);
        JsonNode s1 = sell(owner, pres, 2, "{\"method\":\"NEQUI\",\"amount\":8000,\"reference\":\"M123456\"}", null).expect(201).json();
        JsonNode p1 = s1.get("payments").get(0);
        assertThat(p1.get("status").asText()).isEqualTo("PENDIENTE");
        // El efectivo nace confirmado.
        JsonNode cash = sell(owner, pres, 1, "{\"method\":\"EFECTIVO\",\"amount\":5000}", null).expect(201).json();
        assertThat(cash.get("payments").get(0).get("status").asText()).isEqualTo("CONFIRMADO");

        JsonNode pending = api.get("/api/payments?status=PENDIENTE", owner).expect(200).json();
        assertThat(pending).hasSize(1);
        assertThat(api.get("/api/alerts", owner).expect(200).json().findValuesAsText("type")).contains("PAGOS_POR_VERIFICAR");

        // La misma referencia en otra venta: se marca para revisión.
        JsonNode s2 = sell(owner, pres, 1, "{\"method\":\"NEQUI\",\"amount\":4000,\"reference\":\"M123456\"}", null).expect(201).json();
        assertThat(s2.get("reviewFlags").toString()).contains("REFERENCIA_DUPLICADA");
        assertThat(api.get("/api/alerts", owner).json().findValuesAsText("type")).contains("REFERENCIA_DUPLICADA");

        // El cajero no puede verificar.
        String cajero = newEmployee(store, "CAJERO");
        api.post("/api/payments/" + p1.get("id").asLong() + "/confirm", cajero, null).expect(403);
        JsonNode ok = api.post("/api/payments/" + p1.get("id").asLong() + "/confirm", owner, "{\"note\":\"Visto en la app de Nequi\"}").expect(200).json();
        assertThat(ok.get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(ok.get("verifiedBy").asText()).isEqualTo("Dueño Prueba");
        api.post("/api/payments/" + p1.get("id").asLong() + "/confirm", owner, null).expect(400);

        // El segundo no llegó: se rechaza y la venta queda para revisión; el cliente paga en efectivo.
        long p2 = s2.get("payments").get(0).get("id").asLong();
        api.post("/api/payments/" + p2 + "/reject", owner, "{\"reason\":\"\"}").expect(400);
        assertThat(api.post("/api/payments/" + p2 + "/reject", owner, "{\"reason\":\"No llegó a la cuenta\"}").expect(200).json()
                .get("status").asText()).isEqualTo("RECHAZADO");
        JsonNode sale2 = api.get("/api/pos/sales/" + s2.get("id").asLong(), owner).expect(200).json();
        assertThat(sale2.get("reviewFlags").toString()).contains("PAGO_RECHAZADO");
        JsonNode before = shift(owner);
        api.post("/api/payments/" + p2 + "/replace", owner, "{\"method\":\"EFECTIVO\",\"amount\":3000}").expect(400);
        JsonNode rep = api.post("/api/payments/" + p2 + "/replace", owner, "{\"method\":\"EFECTIVO\",\"amount\":4000}").expect(200).json();
        assertThat(rep.get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(shift(owner).get("expectedCash").decimalValue())
                .isEqualByComparingTo(before.get("expectedCash").decimalValue().add(new BigDecimal("4000")));
        JsonNode events = api.get("/api/payments/events?paymentId=" + p2, owner).expect(200).json();
        assertThat(events.findValuesAsText("type")).contains("REGISTRADO", "REFERENCIA_DUPLICADA", "RECHAZADO", "REEMPLAZADO");
    }

    @Test
    void reconciliationMatchesStatementRows() throws Exception {
        long pres = product("Gaseosa", 1000, 600, 100);
        openShift(owner, 0);
        long a = sell(owner, pres, 5, "{\"method\":\"NEQUI\",\"amount\":5000,\"reference\":\"NQ55501\"}", null).expect(201).json().get("payments").get(0).get("id").asLong();
        long b = sell(owner, pres, 7, "{\"method\":\"NEQUI\",\"amount\":7000}", null).expect(201).json().get("payments").get(0).get("id").asLong();
        long c = sell(owner, pres, 3, "{\"method\":\"NEQUI\",\"amount\":3000,\"reference\":\"NQ55503\"}", null).expect(201).json().get("payments").get(0).get("id").asLong();
        String today = LocalDate.now().toString();
        String csv = "Fecha;Descripción;Referencia;Valor\n"
                + today + ";Pago recibido;NQ55501;$ 5.000\n"
                + today + ";Pago de Ana;;7.000\n"
                + today + ";Pago recibido;NQ55503;2.500\n"
                + today + ";Transferencia;ZZ99999;12.000\n"
                + today + ";Envío a otra cuenta;XX11111;-20.000\n";
        JsonNode r = api.post("/api/payments/reconcile", owner, json.createObjectNode().put("method", "NEQUI").put("fileName", "nequi.csv").put("csv", csv))
                .expect(200).json();
        assertThat(r.get("totalRows").asInt()).isEqualTo(4); // la salida de dinero no cuenta
        assertThat(r.get("matched").asInt()).isEqualTo(1);
        assertThat(r.get("inconsistent").asInt()).isEqualTo(1);
        assertThat(r.get("rows").findValuesAsText("status")).containsExactly("CONCILIADO", "PROBABLE", "INCONSISTENTE", "SIN_REGISTRO");
        JsonNode rows = api.get("/api/payments?method=NEQUI", owner).json();
        for (JsonNode p : rows) {
            long id = p.get("id").asLong();
            if (id == a) assertThat(p.get("status").asText()).isEqualTo("CONFIRMADO");
            if (id == b || id == c) assertThat(p.get("status").asText()).isEqualTo("PENDIENTE");
        }
        assertThat(api.get("/api/payments/reconciliations", owner).expect(200).json()).hasSize(1);
        String cajero = newEmployee(store, "CAJERO");
        api.post("/api/payments/reconcile", cajero, json.createObjectNode().put("method", "NEQUI").put("csv", csv)).expect(403);
    }

    @Test
    void signedWebhookConfirmsPayment() throws Exception {
        long pres = product("Snack", 2500, 1500, 20);
        openShift(owner, 0);
        long pid = sell(owner, pres, 2, "{\"method\":\"BREB\",\"amount\":5000,\"reference\":\"BRB-7781\"}", null).expect(201).json()
                .get("payments").get(0).get("id").asLong();
        JsonNode integ = api.post("/api/payment-integrations", owner, "{\"provider\":\"Wompi\"}").expect(200).json();
        String pk = integ.get("publicKey").asText();
        String sk = integ.get("secret").asText();
        assertThat(api.get("/api/payment-integrations", owner).json().get(0).get("secret").asText()).doesNotContain(sk);
        String body = "{\"reference\":\"BRB-7781\",\"amount\":5000,\"status\":\"APPROVED\",\"transactionId\":\"tx-1\"}";
        // Firma falsa: rechazado.
        var bad = mvc.perform(post("/api/webhooks/payments/" + pk).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Signature", "abc")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        var good = mvc.perform(post("/api/webhooks/payments/" + pk).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Signature", hmac(sk, body))).andReturn();
        assertThat(good.getResponse().getStatus()).isEqualTo(200);
        assertThat(good.getResponse().getContentAsString()).contains("CONFIRMADO");
        JsonNode p = api.get("/api/payments?method=BREB", owner).json().get(0);
        assertThat(p.get("id").asLong()).isEqualTo(pid);
        assertThat(p.get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(p.get("verifiedBy").asText()).isEqualTo("Integración WOMPI");
    }

    @Test
    void paymentAccountsWithQr() throws Exception {
        String qr = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        JsonNode a = api.post("/api/payment-accounts", owner, json.createObjectNode().put("method", "NEQUI").put("label", "Nequi tienda")
                .put("holder", "Dueño").put("accountRef", "3001234567").put("qrImage", qr)).expect(200).json();
        api.post("/api/payment-accounts", owner, json.createObjectNode().put("method", "NEQUI").put("label", "Mala").put("qrImage", "javascript:alert(1)")).expect(400);
        String cajero = newEmployee(store, "CAJERO");
        assertThat(api.get("/api/payment-accounts", cajero).expect(200).json().get(0).get("qrImage").asText()).isEqualTo(qr);
        api.post("/api/payment-accounts", cajero, json.createObjectNode().put("method", "NEQUI").put("label", "x")).expect(403);
        api.call("DELETE", "/api/payment-accounts/" + a.get("id").asLong(), owner, null).expect(204);
    }

    // ---------------- fiado ----------------

    @Test
    void creditSalesLimitsApprovalsAndPayments() throws Exception {
        long pres = product("Arroz", 5000, 3500, 100);
        String cajero = newEmployee(store, "CAJERO");
        openShift(cajero, 10000);
        // Sin activar el fiado no se puede.
        sell(cajero, pres, 1, "{\"method\":\"FIADO\",\"amount\":5000}", null).expect(400);
        enableCredit(30000, 15);
        long ana = customer("Ana Gómez", "1020304050", "3105556677");
        // Sin cliente, no.
        sell(cajero, pres, 1, "{\"method\":\"FIADO\",\"amount\":5000}", null).expect(400);
        // Mixto: 10.000 en efectivo + 20.000 fiado.
        JsonNode s1 = sell(cajero, pres, 6, "{\"method\":\"EFECTIVO\",\"amount\":10000},{\"method\":\"FIADO\",\"amount\":20000}",
                "\"customer\":{\"customerId\":%d}".formatted(ana)).expect(201).json();
        assertThat(s1.get("payments").get(1).get("status").asText()).isEqualTo("CREDITO");
        JsonNode cc = api.get("/api/credit/" + ana, cajero).expect(200).json();
        assertThat(cc.get("balance").decimalValue()).isEqualByComparingTo("20000");
        assertThat(cc.get("status").asText()).isEqualTo("AL_DIA");
        assertThat(cc.get("oldestDue").asText()).isEqualTo(LocalDate.now().plusDays(15).toString());

        // Supera el cupo de 30.000: necesita autorización con PIN de quien tenga FIADO_AUTORIZAR.
        String over = "{\"method\":\"FIADO\",\"amount\":15000}";
        String cust = "\"customer\":{\"customerId\":%d}".formatted(ana);
        Api.Res denied = sell(cajero, pres, 3, over, cust).expect(403);
        assertThat(denied.raw()).contains("FIADO_AUTORIZAR");
        api.post("/api/auth/pin", owner, "{\"currentPassword\":\"Clave1234\",\"pin\":\"4826\"}").expect(204);
        long ownerId = api.get("/api/auth/me", owner).json().get("id").asLong();
        String tok = api.post("/api/pos/approvals", cajero, "{\"approverId\":%d,\"secret\":\"4826\",\"permission\":\"FIADO_AUTORIZAR\"}".formatted(ownerId))
                .expect(200).json().get("token").asText();
        JsonNode s2 = sell(cajero, pres, 3, over, cust + ",\"approvals\":[\"" + tok + "\"]").expect(201).json();
        assertThat(api.get("/api/credit/" + ana, cajero).json().get("balance").decimalValue()).isEqualByComparingTo("35000");

        // Abono en efectivo: entra a la caja del turno y se aplica a la deuda más antigua.
        BigDecimal cashBefore = shift(cajero).get("expectedCash").decimalValue();
        api.post("/api/credit/" + ana + "/payments", cajero, "{\"amount\":50000,\"method\":\"EFECTIVO\"}").expect(400);
        JsonNode afterPay = api.post("/api/credit/" + ana + "/payments", cajero, "{\"amount\":22000,\"method\":\"EFECTIVO\",\"note\":\"Abono quincena\"}").expect(200).json();
        assertThat(afterPay.get("balance").decimalValue()).isEqualByComparingTo("13000");
        assertThat(shift(cajero).get("expectedCash").decimalValue()).isEqualByComparingTo(cashBefore.add(new BigDecimal("22000")));
        assertThat(afterPay.get("statement").findValuesAsText("type")).contains("CARGO", "ABONO");

        // Anular la segunda venta (fiada) deja la deuda saldada en esa parte.
        String sup = newEmployee(store, "SUPERVISOR");
        api.post("/api/pos/sales/" + s2.get("id").asLong() + "/void", sup, "{\"reason\":\"Se equivocó de cliente\"}").expect(200);
        assertThat(api.get("/api/credit/" + ana, cajero).json().get("balance").decimalValue()).isEqualByComparingTo("0");

        // Recordatorio por WhatsApp y bloqueo.
        sell(cajero, pres, 1, "{\"method\":\"FIADO\",\"amount\":5000}", cust).expect(201);
        JsonNode rem = api.get("/api/credit/" + ana + "/reminder", cajero).expect(200).json();
        assertThat(rem.get("whatsappUrl").asText()).startsWith("https://wa.me/573105556677?text=");
        api.put("/api/credit/" + ana + "/terms", cajero, "{\"blocked\":true}").expect(403);
        api.put("/api/credit/" + ana + "/terms", owner, "{\"creditLimit\":50000,\"creditDays\":30,\"blocked\":true}").expect(200);
        sell(cajero, pres, 1, "{\"method\":\"FIADO\",\"amount\":5000}", cust).expect(400);
        assertThat(api.get("/api/credit", owner).expect(200).json()).hasSize(1);
    }

    @Test
    void creditReturnLowersDebt() throws Exception {
        long pres = product("Aceite", 10000, 7000, 10);
        openShift(owner, 0);
        enableCredit(100000, 10);
        long c = customer("Luis Pérez", "79111222", "3001112233");
        JsonNode s = sell(owner, pres, 2, "{\"method\":\"FIADO\",\"amount\":20000}", "\"customer\":{\"customerId\":%d}".formatted(c)).expect(201).json();
        long line = s.get("lines").get(0).get("id").asLong();
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"reason":"Llegó vencido","refundMethod":"FIADO","lines":[{"saleLineId":%d,"quantity":1,"restock":false,"condition":"DANADO"}]}
                """.formatted(line)).expect(200);
        assertThat(api.get("/api/credit/" + c, owner).json().get("balance").decimalValue()).isEqualByComparingTo("10000");
    }

    // ---------------- gastos, por pagar y finanzas ----------------

    @Test
    void expensesFromCashRegisterAndShiftMovementsReachFinance() throws Exception {
        openShift(owner, 100000);
        JsonNode cats = api.get("/api/finance/categories", owner).expect(200).json();
        long arriendo = 0;
        for (JsonNode c : cats) if (c.get("name").asText().equals("Arriendo")) arriendo = c.get("id").asLong();
        assertThat(arriendo).isPositive();
        // Gasto pagado desde el banco: no toca la caja.
        api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"categoryId\":%d,\"description\":\"Arriendo octubre\",\"amount\":800000,\"method\":\"TRANSFERENCIA\",\"paidFrom\":\"BANCO\"}"
                .formatted(arriendo)).expect(200);
        assertThat(shift(owner).get("expectedCash").decimalValue()).isEqualByComparingTo("100000");
        // Gasto de la caja mayor que lo que hay: no.
        api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"description\":\"Domicilio\",\"amount\":150000,\"method\":\"EFECTIVO\",\"paidFrom\":\"CAJA\"}").expect(400);
        JsonNode e = api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"description\":\"Bolsas\",\"amount\":12000,\"method\":\"EFECTIVO\",\"paidFrom\":\"CAJA\"}")
                .expect(200).json();
        assertThat(e.get("categoryName").asText()).isEqualTo("Otros gastos");
        assertThat(e.get("fromCashRegister").asBoolean()).isTrue();
        assertThat(shift(owner).get("expectedCash").decimalValue()).isEqualByComparingTo("88000");
        // Un gasto hecho desde la caja (movimiento del turno) también aparece en finanzas.
        long shiftId = shift(owner).get("id").asLong();
        api.post("/api/shifts/" + shiftId + "/movements", owner, "{\"type\":\"GASTO\",\"amount\":5000,\"reason\":\"Aseo\",\"category\":\"Insumos\"}").expect(200);
        JsonNode list = api.get("/api/finance/entries?kind=GASTO", owner).expect(200).json();
        assertThat(list).hasSize(3);
        assertThat(list.findValuesAsText("categoryName")).contains("Insumos", "Arriendo");
        // Anular devuelve el efectivo a la caja.
        api.post("/api/finance/entries/" + e.get("id").asLong() + "/void", owner, "{\"reason\":\"Se registró dos veces\"}").expect(200);
        assertThat(shift(owner).get("expectedCash").decimalValue()).isEqualByComparingTo("95000");
        JsonNode sum = api.get("/api/finance/summary", owner).expect(200).json();
        assertThat(sum.get("expenses").decimalValue()).isEqualByComparingTo("805000");
        // El cajero no ve finanzas.
        String cajero = newEmployee(store, "CAJERO");
        api.get("/api/finance/summary", cajero).expect(403);
        api.post("/api/finance/entries", cajero, "{\"kind\":\"GASTO\",\"description\":\"x\",\"amount\":1,\"method\":\"EFECTIVO\",\"paidFrom\":\"BANCO\"}").expect(403);
    }

    @Test
    void payablesAndSupplierPaymentsFifo() throws Exception {
        JsonNode p = api.post("/api/products", owner, """
                {"name":"Jugo","unit":"UNIDAD","cost":1000,"initialStock":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":2000,"isBase":true}]}""").expect(201).json();
        long pid = p.get("id").asLong();
        long sup = api.post("/api/suppliers", owner, "{\"name\":\"Distri Jugos\",\"creditDays\":2}").expect(201).json().get("id").asLong();
        long[] receipts = new long[2];
        for (int i = 0; i < 2; i++) {
            JsonNode o = api.post("/api/purchases", owner, "{\"supplierId\":%d,\"lines\":[{\"productId\":%d,\"packs\":10,\"costPerPack\":1000}]}"
                    .formatted(sup, pid)).expect(201).json();
            long id = o.get("id").asLong();
            api.post("/api/purchases/" + id + "/approve", owner, null).expect(200);
            JsonNode rec = api.post("/api/purchases/" + id + "/receive", owner, "{\"invoiceNumber\":\"F-%d\",\"lines\":[{\"orderLineId\":%d,\"unitsReceived\":10}]}"
                    .formatted(i + 1, o.get("lines").get(0).get("id").asLong())).expect(200).json();
            receipts[i] = rec.get("receipts").get(0).get("id").asLong();
        }
        JsonNode pay = api.get("/api/finance/payables", owner).expect(200).json();
        assertThat(pay.get("total").decimalValue()).isEqualByComparingTo("20000");
        assertThat(pay.get("bySupplier").get(0).get("invoices").asInt()).isEqualTo(2);
        assertThat(api.get("/api/alerts", owner).json().findValuesAsText("type")).contains("FACTURA_POR_VENCER");
        // No se puede pagar más de lo que se debe.
        api.post("/api/finance/supplier-payments", owner, "{\"supplierId\":%d,\"amount\":25000,\"method\":\"TRANSFERENCIA\",\"paidFrom\":\"BANCO\"}".formatted(sup)).expect(400);
        // De la caja sin turno abierto: no.
        api.post("/api/finance/supplier-payments", owner, "{\"supplierId\":%d,\"amount\":5000,\"method\":\"EFECTIVO\",\"paidFrom\":\"CAJA\"}".formatted(sup)).expect(409);
        openShift(owner, 50000);
        JsonNode sp = api.post("/api/finance/supplier-payments", owner, "{\"supplierId\":%d,\"amount\":14000,\"method\":\"EFECTIVO\",\"paidFrom\":\"CAJA\"}".formatted(sup))
                .expect(200).json();
        assertThat(sp.get("appliedTo")).hasSize(2);
        assertThat(shift(owner).get("expectedCash").decimalValue()).isEqualByComparingTo("36000");
        pay = api.get("/api/finance/payables?supplierId=" + sup, owner).json();
        assertThat(pay.get("total").decimalValue()).isEqualByComparingTo("6000");
        assertThat(pay.get("invoices")).hasSize(1);
        assertThat(api.get("/api/finance/supplier-payments?supplierId=" + sup, owner).json()).hasSize(1);
        assertThat(api.get("/api/suppliers/" + sup, owner).json().get("metrics").get("balance").decimalValue()).isEqualByComparingTo("6000");
    }

    @Test
    void summaryProfitCashFlowMonthlyAndExport() throws Exception {
        long pres = product("Galletas", 10000, 6000, 50);
        openShift(owner, 0);
        enableCredit(100000, 15);
        long c = customer("Marta", "52000111", "3009998877");
        sell(owner, pres, 3, "{\"method\":\"EFECTIVO\",\"amount\":30000}", null).expect(201);
        sell(owner, pres, 2, "{\"method\":\"NEQUI\",\"amount\":20000,\"reference\":\"QQ12345\"}", null).expect(201);
        sell(owner, pres, 1, "{\"method\":\"FIADO\",\"amount\":10000}", "\"customer\":{\"customerId\":%d}".formatted(c)).expect(201);
        api.post("/api/credit/" + c + "/payments", owner, "{\"amount\":4000,\"method\":\"EFECTIVO\"}").expect(200);
        api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"description\":\"Luz\",\"amount\":7000,\"method\":\"TRANSFERENCIA\",\"paidFrom\":\"BANCO\"}").expect(200);
        api.post("/api/finance/entries", owner, "{\"kind\":\"INGRESO\",\"description\":\"Aporte\",\"amount\":50000,\"method\":\"TRANSFERENCIA\",\"paidFrom\":\"BANCO\"}").expect(200);

        JsonNode s = api.get("/api/finance/summary", owner).expect(200).json();
        assertThat(s.get("salesCount").asInt()).isEqualTo(3);
        assertThat(s.get("netSales").decimalValue()).isEqualByComparingTo("60000");
        assertThat(s.get("costOfSales").decimalValue()).isEqualByComparingTo("36000");
        assertThat(s.get("grossProfit").decimalValue()).isEqualByComparingTo("24000");
        assertThat(s.get("grossMarginPct").decimalValue()).isEqualByComparingTo("40.0");
        assertThat(s.get("netProfit").decimalValue()).isEqualByComparingTo("17000");
        // Entradas: 30.000 efectivo + 20.000 Nequi + 4.000 abono + 50.000 aporte (el fiado no es dinero).
        assertThat(s.get("inflow").decimalValue()).isEqualByComparingTo("104000");
        assertThat(s.get("outflow").decimalValue()).isEqualByComparingTo("7000");
        assertThat(s.get("receivables").decimalValue()).isEqualByComparingTo("6000");
        assertThat(s.get("creditSales").decimalValue()).isEqualByComparingTo("10000");
        assertThat(s.get("pendingVerification").decimalValue()).isEqualByComparingTo("20000");
        assertThat(s.get("days")).isNotEmpty();

        JsonNode m = api.get("/api/finance/monthly", owner).expect(200).json();
        assertThat(m.get("current").get("netProfit").decimalValue()).isEqualByComparingTo("17000");
        Api.Res csv = api.get("/api/finance/export", owner).expect(200);
        assertThat(csv.raw()).contains("Utilidad neta estimada;17000").contains("Luz");
    }
}
