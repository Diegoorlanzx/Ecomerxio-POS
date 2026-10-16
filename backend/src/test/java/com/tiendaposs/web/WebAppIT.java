package com.tiendaposs.web;

import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Fase 7: el servidor entrega la app web (rutas de la app, archivos, caché y encabezados de seguridad). */
class WebAppIT extends AbstractIntegrationTest {

    MvcResult go(String url) throws Exception {
        return mvc.perform(get(url)).andReturn();
    }

    @Test
    void appRoutesServeIndexButAssetsAndApiKeepTheirOwn() throws Exception {
        for (String route : new String[]{"/", "/pos", "/inventario/conteos/3", "/fiados/12"}) {
            MvcResult r = go(route);
            assertThat(r.getResponse().getStatus()).as(route).isEqualTo(200);
            assertThat(r.getResponse().getForwardedUrl()).as(route).isEqualTo("/index.html");
            assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        }
        MvcResult asset = go("/assets/app-test123.js");
        assertThat(asset.getResponse().getStatus()).isEqualTo(200);
        assertThat(asset.getResponse().getContentAsString()).contains("console.log");
        assertThat(asset.getResponse().getForwardedUrl()).isNull();
        assertThat(asset.getResponse().getHeader("Cache-Control")).contains("immutable");
        // El tipo application/manifest+json lo pone el servidor web real (MimeTypes); MockMvc no lo usa.
        assertThat(go("/api/products").getResponse().getStatus()).isEqualTo(401);
        String csp = go("/pos").getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'");
        // Sin paquete de Android configurado no se publica assetlinks.
        assertThat(go("/.well-known/assetlinks.json").getResponse().getStatus()).isEqualTo(404);
    }
}
