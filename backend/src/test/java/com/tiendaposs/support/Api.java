package com.tiendaposs.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Atajo para llamar la API en las pruebas: call(...).expect(201).json(). */
public final class Api {

    private final MockMvc mvc;
    private final ObjectMapper json;

    public Api(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public record Res(int status, JsonNode body, String raw) {
        public Res expect(int code) {
            assertThat(status).as("HTTP %s -> %s", code, raw).isEqualTo(code);
            return this;
        }

        public JsonNode json() {
            return body;
        }
    }

    public Res call(String method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), url);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(b).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String type = r.getResponse().getContentType();
        boolean isJson = !raw.isEmpty() && type != null && type.contains("json");
        return new Res(r.getResponse().getStatus(), isJson ? json.readTree(raw) : json.createObjectNode(), raw);
    }

    public Res get(String url, String token) throws Exception {
        return call("GET", url, token, null);
    }

    public Res post(String url, String token, Object body) throws Exception {
        return call("POST", url, token, body == null ? "{}" : body);
    }

    public Res put(String url, String token, Object body) throws Exception {
        return call("PUT", url, token, body);
    }
}
