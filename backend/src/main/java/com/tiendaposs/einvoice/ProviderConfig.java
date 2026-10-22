package com.tiendaposs.einvoice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Fase 16D: qué proveedor tecnológico usa la plataforma (EINVOICE_PROVIDER = SIMULADO o FACTUS).
 * Con Factus, los documentos del ambiente de pruebas van al sandbox y los de producción a la API real, cada uno con sus
 * credenciales (FACTUS_SANDBOX_* y FACTUS_PROD_*). Las credenciales nunca van en git: .env.local o variables del servidor.
 */
@Configuration
public class ProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.einvoice.provider", havingValue = "FACTUS")
    EInvoiceProvider factus(@Value("${app.einvoice.factus.sandbox.url:https://api-sandbox.factus.com.co}") String sUrl,
                            @Value("${app.einvoice.factus.sandbox.client-id:}") String sId,
                            @Value("${app.einvoice.factus.sandbox.client-secret:}") String sSecret,
                            @Value("${app.einvoice.factus.sandbox.username:}") String sUser,
                            @Value("${app.einvoice.factus.sandbox.password:}") String sPass,
                            @Value("${app.einvoice.factus.prod.url:https://api.factus.com.co}") String pUrl,
                            @Value("${app.einvoice.factus.prod.client-id:}") String pId,
                            @Value("${app.einvoice.factus.prod.client-secret:}") String pSecret,
                            @Value("${app.einvoice.factus.prod.username:}") String pUser,
                            @Value("${app.einvoice.factus.prod.password:}") String pPass) {
        FactusProvider sandbox = sId.isBlank() ? null : new FactusProvider(new FactusProvider.Config(sUrl, sId, sSecret, sUser, sPass));
        FactusProvider prod = pId.isBlank() ? null : new FactusProvider(new FactusProvider.Config(pUrl, pId, pSecret, pUser, pPass));
        if (sandbox == null && prod == null) throw new IllegalStateException("EINVOICE_PROVIDER=FACTUS sin credenciales FACTUS_SANDBOX_* ni FACTUS_PROD_*");
        return new Router(sandbox, prod);
    }

    /** Envía según el ambiente del documento; consulta en ambos (las referencias no se repiten entre ambientes). */
    static final class Router implements EInvoiceProvider {
        private final FactusProvider sandbox, prod;

        Router(FactusProvider sandbox, FactusProvider prod) {
            this.sandbox = sandbox;
            this.prod = prod;
        }

        @Override
        public String code() {
            return "FACTUS";
        }

        @Override
        public boolean simulated() {
            return false;
        }

        @Override
        public Result send(Payload d) throws ProviderUnavailableException {
            FactusProvider p = "PRODUCCION".equals(d.environment()) ? prod : sandbox;
            if (p == null) {
                return new Result(Outcome.REJECTED, null, null, null, List.of(new ProviderError("CONFIG_PROVEEDOR",
                        "La plataforma no tiene credenciales de Factus para el ambiente de " + d.environment().toLowerCase() + ".")));
            }
            return p.send(d);
        }

        @Override
        public Result sendPayroll(PayrollPayload d) throws ProviderUnavailableException {
            FactusProvider p = "PRODUCCION".equals(d.environment()) ? prod : sandbox;
            if (p == null) {
                return new Result(Outcome.REJECTED, null, null, null, List.of(new ProviderError("CONFIG_PROVEEDOR",
                        "La plataforma no tiene credenciales de Factus para el ambiente de " + d.environment().toLowerCase() + ".")));
            }
            return p.sendPayroll(d);
        }

        @Override
        public Result query(String externalRef) throws ProviderUnavailableException {
            if (sandbox != null) {
                Result r = sandbox.query(externalRef);
                if (r.outcome() != Outcome.NOT_FOUND || prod == null) return r;
            }
            return prod.query(externalRef);
        }
    }
}
