package com.tiendaposs.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envío de correos. Sin servidor SMTP configurado (desarrollo) el mensaje se escribe en el log
 * para poder probar los flujos completos sin depender de un proveedor externo.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail-from}") String from) {
        this.sender = sender;
        this.host = host;
        this.from = from;
    }

    /** Hay un servidor SMTP configurado (si no, los correos solo se escriben en el log). */
    public boolean configured() {
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    public void send(String to, String subject, String body) {
        if (host == null || host.isBlank() || sender.getIfAvailable() == null) {
            log.info("[CORREO SIN ENVIAR - no hay SMTP] Para: {} | Asunto: {}\n{}", to, subject, body);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.getIfAvailable().send(msg);
        } catch (Exception e) {
            log.warn("No se pudo enviar el correo a {}: {}", to, e.getMessage());
        }
    }
}
