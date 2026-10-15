package com.tiendaposs.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Acceso a la API de Claude con el SDK oficial. Sin clave configurada, el asistente usa solo consultas rápidas. */
@Component
public class ClaudeGateway {

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public ClaudeGateway(@Value("${app.ai.api-key:}") String apiKey, @Value("${app.ai.model:claude-opus-5-5}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    public Message create(MessageCreateParams params) {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
        }
        return client.messages().create(params);
    }
}
