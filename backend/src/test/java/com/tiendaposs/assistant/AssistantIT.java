package com.tiendaposs.assistant;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fase 6: asistente IA. Las respuestas de Claude se simulan (no se gasta en la API): se prueba el
 * ciclo de herramientas, el historial que solo crece, los permisos, el límite diario y las
 * recomendaciones de precios con aprobación humana.
 */
class AssistantIT extends AbstractIntegrationTest {

    @MockBean
    ClaudeGateway claude;

    Api api;
    Store store;
    String owner;
    long presA;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        reset(claude);
        when(claude.configured()).thenReturn(true);
        when(claude.model()).thenReturn("claude-opus-5-5");
        presA = api.post("/api/products", owner, """
                {"name":"Gaseosa Litro","unit":"UNIDAD","cost":950,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":1000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
        for (int i = 0; i < 3; i++) {
            api.post("/api/pos/sales", owner, "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":5}],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":5000}]}"
                    .formatted(UUID.randomUUID(), presA)).expect(201);
        }
    }

    static Message msg(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, Message.class);
    }

    static Message toolCall(String id, String tool, String input) throws Exception {
        return msg("""
                {"id":"msg_%s","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"tool_use","id":"toolu_%s","name":"%s","input":%s}],
                 "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":40}}""".formatted(id, id, tool, input));
    }

    static Message text(String id, String text) throws Exception {
        return msg("""
                {"id":"msg_%s","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":%s}],
                 "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1500,"output_tokens":80}}"""
                .formatted(id, ObjectMappers.jsonMapper().writeValueAsString(text)));
    }

    @Test
    void quickQueriesWorkWithoutAiAndRespectPermissions() throws Exception {
        for (String tool : List.of("ventas_periodo", "productos_rentabilidad", "sugerencias_compra", "productos_en_caida", "comparar_proveedores",
                "resumen_periodo", "detectar_anomalias", "estado_inventario")) {
            JsonNode r = api.post("/api/assistant/quick", owner, "{\"tool\":\"%s\",\"input\":{\"tipo\":\"semana\"}}".formatted(tool)).expect(200).json();
            assertThat(r.get("resumen").asText()).as(tool).isNotBlank();
        }
        JsonNode sales = api.post("/api/assistant/quick", owner, "{\"tool\":\"ventas_periodo\",\"input\":{}}").json();
        assertThat(sales.get("resumen").asText()).contains("ventas $15.000 en 3 transacciones");
        assertThat(api.post("/api/assistant/quick", owner, "{\"tool\":\"estado_inventario\",\"input\":{\"buscar\":\"gaseosa\"}}").json()
                .get("resumen").asText()).contains("Gaseosa Litro: 85 en existencia");

        // Sin permiso del asistente: nada.
        String cajero = newEmployee(store, "CAJERO");
        api.post("/api/assistant/quick", cajero, "{\"tool\":\"ventas_periodo\",\"input\":{}}").expect(403);
        // Con asistente pero sin costos: no ve la herramienta de rentabilidad.
        long role = api.post("/api/roles", owner, "{\"name\":\"Analista ventas\",\"permissions\":[\"ASISTENTE_IA\",\"REPORTES_VER\"]}").expect(201).json().get("id").asLong();
        String email = "analista" + UUID.randomUUID().toString().substring(0, 6) + "@prueba.com";
        api.post("/api/users", owner, "{\"fullName\":\"Ana Analista\",\"email\":\"%s\",\"password\":\"Clave1234\",\"roleIds\":[%d]}".formatted(email, role)).expect(201);
        String analyst = login(email, "Clave1234");
        api.post("/api/assistant/quick", analyst, "{\"tool\":\"productos_rentabilidad\",\"input\":{}}").expect(403);
        JsonNode st = api.get("/api/assistant/status", analyst).expect(200).json();
        assertThat(st.get("tools").findValuesAsText("name")).contains("ventas_periodo").doesNotContain("productos_rentabilidad", "proponer_precios_promociones");
        // Sin costos, la venta no muestra utilidad.
        assertThat(api.post("/api/assistant/quick", analyst, "{\"tool\":\"ventas_periodo\",\"input\":{}}").json().get("resumen").asText())
                .doesNotContain("Utilidad");
    }

    @Test
    void askRunsToolsKeepsAppendOnlyHistoryAndCountsUsage() throws Exception {
        when(claude.create(any())).thenReturn(
                toolCall("1", "ventas_periodo", "{\"desde\":\"2000-01-01\"}"),
                text("2", "Este mes vendiste **$15.000** en 3 ventas."),
                text("3", "Tu producto más vendido es Gaseosa Litro."));
        JsonNode a = api.post("/api/assistant/ask", owner, "{\"question\":\"¿Cuánto vendí este mes?\"}").expect(200).json();
        assertThat(a.get("answer").get("text").asText()).contains("$15.000");
        assertThat(a.get("answer").get("toolsUsed").findValuesAsText("")).isEmpty();
        assertThat(a.get("answer").get("toolsUsed").get(0).asText()).isEqualTo("ventas_periodo");
        long conv = a.get("conversationId").asLong();

        ArgumentCaptor<MessageCreateParams> cap = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(claude, times(2)).create(cap.capture());
        MessageCreateParams second = cap.getAllValues().get(1);
        assertThat(second.model().asString()).isEqualTo("claude-opus-5-5");
        List<MessageParam> msgs = second.messages();
        assertThat(msgs).hasSize(3); // pregunta, llamada a la herramienta, resultado
        String toolResult = ObjectMappers.jsonMapper().writeValueAsString(msgs.get(2));
        assertThat(toolResult).contains("tool_result").contains("toolu_1").contains("transacciones");
        assertThat(second.tools().orElseThrow()).hasSizeGreaterThanOrEqualTo(9);

        // Segunda pregunta: se reenvía el historial exacto y solo se agrega al final.
        api.post("/api/assistant/ask", owner, "{\"conversationId\":%d,\"question\":\"¿Y el más vendido?\"}".formatted(conv)).expect(200);
        verify(claude, times(3)).create(cap.capture());
        List<MessageParam> third = cap.getAllValues().get(cap.getAllValues().size() - 1).messages();
        assertThat(third).hasSize(5);
        for (int i = 0; i < 3; i++) {
            assertThat(ObjectMappers.jsonMapper().writeValueAsString(third.get(i))).isEqualTo(ObjectMappers.jsonMapper().writeValueAsString(msgs.get(i)));
        }

        JsonNode c = api.get("/api/assistant/conversations/" + conv, owner).expect(200).json();
        assertThat(c.get("messages")).hasSize(4);
        assertThat(c.get("title").asText()).isEqualTo("¿Cuánto vendí este mes?");
        assertThat(api.get("/api/assistant/status", owner).json().get("usedToday").asInt()).isEqualTo(2);
        // Otra persona no puede abrir esa conversación.
        String contador = newEmployee(store, "CONTADOR");
        api.get("/api/assistant/conversations/" + conv, contador).expect(404);

        // Límite diario.
        api.put("/api/assistant/settings", owner, "{\"aiEnabled\":true,\"aiDailyLimit\":2}").expect(200);
        api.post("/api/assistant/ask", owner, "{\"question\":\"¿Otra?\"}").expect(429);
        api.put("/api/assistant/settings", owner, "{\"aiEnabled\":false,\"aiDailyLimit\":50}").expect(200);
        api.post("/api/assistant/ask", owner, "{\"question\":\"¿Otra?\"}").expect(400);
        api.call("DELETE", "/api/assistant/conversations/" + conv, owner, null).expect(204);
        assertThat(api.get("/api/assistant/conversations", owner).json()).isEmpty();
    }

    @Test
    void withoutApiKeyAskExplainsQuickMode() throws Exception {
        when(claude.configured()).thenReturn(false);
        JsonNode r = api.post("/api/assistant/ask", owner, "{\"question\":\"Hola\"}").expect(503).json();
        assertThat(r.get("fields").get("ai").asText()).isEqualTo("SIN_CLAVE");
        assertThat(api.get("/api/assistant/status", owner).json().get("aiConfigured").asBoolean()).isFalse();
        verify(claude, never()).create(any());
    }

    @Test
    void priceRecommendationsNeedHumanApproval() throws Exception {
        // Gaseosa Litro: precio 1.000, costo 950 -> margen 5% (bajo).
        JsonNode r = api.post("/api/assistant/quick", owner, "{\"tool\":\"proponer_precios_promociones\",\"input\":{}}").expect(200).json();
        assertThat(r.get("resumen").asText()).contains("Gaseosa Litro");
        JsonNode list = api.get("/api/recommendations?status=PENDIENTE", owner).expect(200).json();
        JsonNode price = null;
        for (JsonNode x : list) if (x.get("type").asText().equals("PRECIO")) price = x;
        assertThat(price).isNotNull();
        assertThat(price.get("suggestedPrice").decimalValue()).isEqualByComparingTo("1150"); // tope de +15%, redondeado a 50
        assertThat(price.get("source").asText()).isEqualTo("ASISTENTE");
        // El precio no cambió solo.
        long pid = price.get("productId").asLong();
        assertThat(api.get("/api/products/" + pid, owner).json().get("presentations").get(0).get("price").decimalValue()).isEqualByComparingTo("1000");
        // Sin duplicados al volver a generar.
        api.post("/api/recommendations/generate", owner, null).expect(200);
        assertThat(api.get("/api/recommendations?status=PENDIENTE", owner).json()).hasSize(list.size());
        // El contador ve, pero no puede aprobar (no tiene permiso de precios).
        String contador = newEmployee(store, "CONTADOR");
        api.post("/api/recommendations/" + price.get("id").asLong() + "/approve", contador, null).expect(403);
        JsonNode ok = api.post("/api/recommendations/" + price.get("id").asLong() + "/approve", owner, "{\"note\":\"Revisé competencia\"}").expect(200).json();
        assertThat(ok.get("status").asText()).isEqualTo("APLICADA");
        assertThat(api.get("/api/products/" + pid, owner).json().get("presentations").get(0).get("price").decimalValue()).isEqualByComparingTo("1150");
        assertThat(api.get("/api/products/" + pid + "/price-history", owner).json().get(0).get("reason").asText()).contains("Recomendación #");
        api.post("/api/recommendations/" + price.get("id").asLong() + "/dismiss", owner, null).expect(400);
    }
}
