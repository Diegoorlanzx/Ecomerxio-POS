package com.tiendaposs.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * En producción el servidor también entrega la app web (compilada dentro del .jar en /static):
 * las rutas de la app (/pos, /caja…) devuelven index.html, los archivos con huella se guardan un año
 * en caché y el index, el service worker y el manifiesto nunca se guardan (así siempre llega la versión nueva).
 * También publica /.well-known/assetlinks.json para la app de Play Store (TWA).
 */
public final class WebAppConfig {
    private WebAppConfig() {
    }

    @Controller
    public static class SpaController {
        private final boolean hasWebApp = new ClassPathResource("static/index.html").exists();

        /** Cualquier ruta sin punto que no sea de la API es una pantalla de la app. */
        @GetMapping({"/", "/{path:^(?!api$|assets$|\\.well-known$)[^.]*}", "/{path:^(?!api$|assets$|\\.well-known$)[^.]*}/**"})
        public Object app(HttpServletRequest req) {
            if (!hasWebApp) return ResponseEntity.notFound().build();
            return "forward:/index.html";
        }
    }

    @Controller
    public static class AssetLinksController {
        private final String packageName;
        private final List<String> fingerprints;

        public AssetLinksController(@Value("${app.android.package:}") String packageName, @Value("${app.android.sha256:}") String sha256) {
            this.packageName = packageName.trim();
            this.fingerprints = Arrays.stream(sha256.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        /** Asocia el dominio con la app de Android (Trusted Web Activity) para abrirla sin barra del navegador. */
        @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
        @ResponseBody
        public ResponseEntity<List<Map<String, Object>>> assetLinks() {
            if (packageName.isEmpty() || fingerprints.isEmpty()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(List.of(Map.of(
                    "relation", List.of("delegate_permission/common.handle_all_urls"),
                    "target", Map.of("namespace", "android_app", "package_name", packageName, "sha256_cert_fingerprints", fingerprints))));
        }
    }

    /** El manifiesto de la app instalable necesita su tipo propio para que los navegadores lo acepten. */
    @Component
    public static class MimeTypes implements org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory> {
        @Override
        public void customize(org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory factory) {
            org.springframework.boot.web.server.MimeMappings mappings = new org.springframework.boot.web.server.MimeMappings(org.springframework.boot.web.server.MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        }
    }

    @Component
    public static class CacheHeadersFilter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return request.getRequestURI().startsWith("/api/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String uri = req.getRequestURI();
            if (uri.startsWith("/assets/")) {
                res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (uri.equals("/sw.js") || uri.equals("/remoteEntry.js")) {
                // remoteEntry.js: lo que carga ecomerxio.com/pos (microfrontend); debe ser siempre el del último despliegue.
                res.setHeader("Cache-Control", "no-cache");
                res.setHeader("Service-Worker-Allowed", "/");
            } else if (!uri.contains(".") || uri.endsWith(".html") || uri.endsWith(".json") || uri.endsWith(".webmanifest")) {
                res.setHeader("Cache-Control", "no-cache");
            } else {
                res.setHeader("Cache-Control", "public, max-age=86400");
            }
            chain.doFilter(req, res);
        }
    }
}
