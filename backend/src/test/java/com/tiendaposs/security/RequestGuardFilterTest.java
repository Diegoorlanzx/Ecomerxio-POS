package com.tiendaposs.security;

import com.tiendaposs.auth.RateLimiter;
import com.tiendaposs.web.RequestGuardFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Escudo de la API: tope de peticiones por IP, más estricto sin sesión, IPv6 por bloque /64 y tope de tamaño. */
class RequestGuardFilterTest {

    private final RequestGuardFilter filter = new RequestGuardFilter(new RateLimiter(true, 20, 60), 10, 3, 1, 2);

    private int call(String path, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void floodFromOneIpIsCutButOthersStillWork() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(call("/api/products", "10.0.0.1")).isEqualTo(200);
        assertThat(call("/api/products", "10.0.0.1")).isEqualTo(429);
        assertThat(call("/api/products", "10.0.0.2")).isEqualTo(200);
        // El chequeo de salud de Render y la app web no cuentan.
        assertThat(call("/api/health", "10.0.0.1")).isEqualTo(200);
        assertThat(call("/index.html", "10.0.0.1")).isEqualTo(200);
    }

    @Test
    void publicRoutesHaveATighterLimit() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(call("/api/public/stores/x", "10.0.0.3")).isEqualTo(200);
        assertThat(call("/api/public/stores/x", "10.0.0.3")).isEqualTo(429);
        assertThat(call("/api/products", "10.0.0.3")).isEqualTo(200);
    }

    @Test
    void ipv6AddressesOfTheSameBlockShareTheLimit() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(call("/api/products", "2001:db8:1:2:0:0:0:" + Integer.toHexString(i + 1))).isEqualTo(200);
        assertThat(call("/api/products", "2001:db8:1:2:ffff:0:0:1")).isEqualTo(429);
        assertThat(call("/api/products", "2001:db8:1:3:0:0:0:1")).isEqualTo(200);
    }

    @Test
    void oversizedBodyIsRejectedBeforeReadingIt() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/products");
        req.setRequestURI("/api/products");
        req.setRemoteAddr("10.0.0.4");
        req.setContent(new byte[2 * 1024 * 1024]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(413);
    }
}
