package com.tiendaposs.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/** La IP del cliente (límite de intentos, auditoría) no se puede inventar con X-Forwarded-For. */
class ClientIpTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void ignoraXForwardedForEscritoPorElCliente() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.7");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(AuditService.clientIp()).isEqualTo("203.0.113.7");
    }
}
