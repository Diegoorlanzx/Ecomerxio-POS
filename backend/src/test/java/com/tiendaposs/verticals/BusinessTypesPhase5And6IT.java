package com.tiendaposs.verticals;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tipos de negocio, fases 5 y 6: veterinaria, óptica, papelería, agro, mayores de edad, gimnasio y varias cajas a la vez. */
class BusinessTypesPhase5And6IT extends AbstractIntegrationTest {

    private String register(String type) throws Exception {
        String email = unique("f5") + "@prueba.com";
        return body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"Negocio %s","ownerName":"Dueño","email":"%s","password":"Clave1234","businessType":"%s","plan":"PRO",
                 "acceptTerms":true,"acceptPrivacy":true}""".formatted(type, email, type))).andExpect(status().isCreated())).get("token").asText();
    }

    private JsonNode product(Api api, String owner, String name, int price, int stock) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":1000,"initialStock":%d,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}""".formatted(name, stock, price)).expect(201).json();
    }

    private long pres(JsonNode p) {
        return p.get("presentations").get(0).get("id").asLong();
    }

    private void openShift(Api api, String token, long register) throws Exception {
        api.post("/api/shifts/open", token, "{\"registerId\":%d,\"openingCash\":0}".formatted(register)).expect(200);
    }

    @Test
    void veterinariaRegistraMascotasYVacunasPorVencer() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("VETERINARIA");
        long pet = api.post("/api/pets", owner, """
                {"ownerName":"Laura","ownerPhone":"3001234567","name":"Max","species":"Perro","breed":"Criollo","birthDate":"2022-05-01"}""")
                .expect(200).json().get("id").asLong();
        api.post("/api/pets/" + pet + "/vaccines", owner, """
                {"vaccine":"Rabia","appliedOn":"%s","nextDue":"%s"}""".formatted(LocalDate.now().minusYears(1), LocalDate.now().plusDays(5))).expect(200);
        api.post("/api/pets/" + pet + "/vaccines", owner, "{\"vaccine\":\"\"}").expect(400);
        JsonNode due = api.get("/api/pets/vaccines/due?days=10", owner).expect(200).json();
        assertThat(due).hasSize(1);
        assertThat(due.get(0).get("petName").asText()).isEqualTo("Max");
        assertThat(api.get("/api/pets?q=laura", owner).json().get(0).get("vaccines")).hasSize(1);
        // Una tienda sin el módulo no ve mascotas.
        api.get("/api/pets", register("TIENDA")).expect(409);
    }

    @Test
    void opticaGuardaLaFormulaYValidaRangos() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("OPTICA");
        api.post("/api/optical-prescriptions", owner, """
                {"patientName":"Marta","examDate":"%s","odSphere":-1.25,"odCylinder":-0.5,"odAxis":180,"oiSphere":-1.0,"addition":1.5,"pupillaryDistance":62}"""
                .formatted(LocalDate.now())).expect(200);
        api.post("/api/optical-prescriptions", owner, "{\"patientName\":\"X\",\"odAxis\":200}").expect(400);
        JsonNode list = api.get("/api/optical-prescriptions?q=mar", owner).expect(200).json();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("odSphere").decimalValue()).isEqualByComparingTo("-1.25");
        assertThat(api.get("/api/auth/me", owner).json().get("modules").toString()).contains("ORDENES").doesNotContain("SERVICIOS");
        // Las órdenes con anticipo funcionan con el módulo ORDENES, sin agenda.
        api.post("/api/service-orders", owner, """
                {"customerName":"Marta","customerPhone":"3001234567","device":"Lentes progresivos","problem":"Fórmula del 28 oct"}""").expect(200);
    }

    @Test
    void papeleriaArmaListasDeUtiles() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("PAPELERIA");
        long cuaderno = pres(product(api, owner, "Cuaderno 100 hojas", 4500, 100));
        long lapiz = pres(product(api, owner, "Lápiz HB", 800, 100));
        long id = api.post("/api/school-lists", owner, """
                {"school":"Colegio San José","grade":"Tercero","items":[{"presentationId":%d,"quantity":5},{"presentationId":%d,"quantity":3,"note":"marcados"}]}"""
                .formatted(cuaderno, lapiz)).expect(200).json().get("id").asLong();
        JsonNode lists = api.get("/api/school-lists", owner).expect(200).json();
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).get("total").decimalValue()).isEqualByComparingTo("24900");
        api.call("DELETE", "/api/school-lists/" + id, owner, null).expect(200);
        assertThat(api.get("/api/school-lists", owner).json()).isEmpty();
    }

    @Test
    void agroYMayoresDeEdadEnElProducto() throws Exception {
        Api api = new Api(mvc, json);
        String agro = register("AGRO");
        long p = product(api, agro, "Fungicida 1 L", 38000, 10).get("id").asLong();
        assertThat(api.put("/api/products/" + p + "/extras", agro, "{\"icaCode\":\"ICA 2345\",\"adultsOnly\":true}").expect(200).json()
                .get("adultsOnly").asBoolean()).isFalse(); // agro no tiene el módulo de mayores de edad
        assertThat(api.get("/api/products/" + p, agro).json().get("icaCode").asText()).isEqualTo("ICA 2345");

        String licorera = register("LICORERA");
        long ron = product(api, licorera, "Ron 750 ml", 45000, 10).get("id").asLong();
        api.put("/api/products/" + ron + "/extras", licorera, "{\"adultsOnly\":true}").expect(200);
        assertThat(api.get("/api/pos/catalog", licorera).json().toString()).contains("\"adultsOnly\":true");
        api.put("/api/products/" + ron + "/extras", register("SERVICIOS"), "{\"adultsOnly\":true}").expect(409);
    }

    @Test
    void gimnasioVendeMembresiasYControlaLaEntrada() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("GIMNASIO");
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        openShift(api, owner, reg);
        JsonNode mes = api.post("/api/products", owner, """
                {"name":"Mensualidad","unit":"UNIDAD","trackStock":false,"presentations":[{"name":"Mes","factor":1,"price":80000,"isBase":true}]}""")
                .expect(201).json();
        api.put("/api/products/" + mes.get("id").asLong() + "/membership", owner, "{\"days\":30}").expect(200);
        long socio = api.post("/api/pos/customers", owner, "{\"name\":\"Andrés Socio\",\"docNumber\":\"1012345678\"}").expect(201).json().get("id").asLong();

        // Sin cliente no se vende una membresía.
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":80000}]}"""
                .formatted(UUID.randomUUID(), pres(mes))).expect(400);
        assertThat(api.post("/api/memberships/" + socio + "/checkin", owner, null).expect(200).json().get("allowed").asBoolean()).isFalse();
        for (int i = 0; i < 2; i++) {
            api.post("/api/pos/sales", owner, """
                    {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"customer":{"customerId":%d},"payments":[{"method":"EFECTIVO","amount":80000}]}"""
                    .formatted(UUID.randomUUID(), pres(mes), socio)).expect(201);
        }
        JsonNode in = api.post("/api/memberships/" + socio + "/checkin", owner, null).expect(200).json();
        assertThat(in.get("allowed").asBoolean()).isTrue();
        // Dos mensualidades seguidas = 60 días.
        assertThat(in.at("/member/endDate").asText()).isEqualTo(LocalDate.now().plusDays(59).toString());
        assertThat(api.get("/api/memberships?q=1012", owner).json().get(0).get("lastCheckin").isNull()).isFalse();
    }

    @Test
    void supermercadoVendeDesdeVariasCajasALaVez() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("SUPERMERCADO");
        long reg1 = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        long reg2 = api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(200).json().get("id").asLong();
        long rol = roleId(owner, "CAJERO");
        String email = unique("cajera") + "@prueba.com";
        api.post("/api/users", owner, """
                {"fullName":"Cajera Dos","email":"%s","password":"Clave1234","roleIds":[%d]}""".formatted(email, rol)).expect(201);
        String cajera = login(email, "Clave1234");
        openShift(api, owner, reg1);
        openShift(api, cajera, reg2);
        long arroz = pres(product(api, owner, "Arroz 500 g", 2500, 30));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String who = i % 2 == 0 ? owner : cajera;
            results.add(pool.submit(() -> api.post("/api/pos/sales", who, """
                    {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":2500}]}"""
                    .formatted(UUID.randomUUID(), arroz)).status()));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        for (Future<Integer> f : results) assertThat(f.get()).isEqualTo(201);

        JsonNode sales = api.get("/api/pos/sales?size=50", owner).expect(200).json();
        JsonNode items = sales.has("items") ? sales.get("items") : sales;
        Set<Long> numbers = new HashSet<>();
        items.forEach(s -> numbers.add(s.get("number").asLong()));
        assertThat(numbers).hasSize(20);
        assertThat(api.get("/api/pos/catalog", owner).json().toString()).contains("Arroz 500 g");
        JsonNode prod = null;
        for (JsonNode p : api.get("/api/pos/catalog", owner).json()) if (p.get("name").asText().equals("Arroz 500 g")) prod = p;
        assertThat(prod.get("stock").decimalValue()).isEqualByComparingTo("10");
    }
}
