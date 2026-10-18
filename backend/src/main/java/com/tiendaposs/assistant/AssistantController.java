package com.tiendaposs.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.assistant.AssistantService.*;
import com.tiendaposs.assistant.RecommendationService.RecommendationDto;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService assistant;
    private final RecommendationService recommendations;
    private final SettingsService settings;
    private final AuditService audit;

    /** Interruptor de plataforma (ASSISTANT_ENABLED): apagado, el asistente no existe para nadie. */
    private final boolean enabled;

    public AssistantController(AssistantService assistant, RecommendationService recommendations, SettingsService settings, AuditService audit,
                               @org.springframework.beans.factory.annotation.Value("${app.ai.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.assistant = assistant;
        this.recommendations = recommendations;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/assistant/status")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public Status status() {
        requireEnabled();
        return assistant.status();
    }

    public record AskRequest(Long conversationId, @NotBlank(message = "escribe tu pregunta") @Size(max = 2000, message = "máximo 2.000 caracteres") String question) {
    }

    @PostMapping("/assistant/ask")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public AskResult ask(@Valid @RequestBody AskRequest r) {
        requireEnabled();
        return assistant.ask(r.conversationId(), r.question());
    }

    public record QuickRequest(@NotBlank String tool, JsonNode input) {
    }

    @PostMapping("/assistant/quick")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public Map<String, Object> quick(@Valid @RequestBody QuickRequest r) {
        requireEnabled();
        return assistant.quick(r.tool(), r.input());
    }

    @GetMapping("/assistant/conversations")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public List<ConversationDto> conversations() {
        requireEnabled();
        return assistant.conversations();
    }

    @GetMapping("/assistant/conversations/{id}")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public ConversationDto conversation(@PathVariable Long id) {
        requireEnabled();
        return assistant.conversation(id);
    }

    @DeleteMapping("/assistant/conversations/{id}")
    @PreAuthorize("hasAuthority('ASISTENTE_IA')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        requireEnabled();
        assistant.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record AiSettings(boolean aiEnabled, @Min(value = 0, message = "no puede ser negativo") @Max(value = 1000, message = "máximo 1.000") int aiDailyLimit) {
    }

    @PutMapping("/assistant/settings")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    @Transactional
    public AiSettings saveSettings(@Valid @RequestBody AiSettings r) {
        requireEnabled();
        StoreSettings st = settings.current();
        st.aiEnabled = r.aiEnabled();
        st.aiDailyLimit = r.aiDailyLimit();
        st.updatedAt = Instant.now();
        audit.sensitive("CONFIG_ASISTENTE", "TENANT", st.tenantId, (r.aiEnabled() ? "Activó" : "Desactivó") + " el asistente IA (límite " + r.aiDailyLimit() + " preguntas al día)");
        return r;
    }

    private void requireEnabled() {
        if (!enabled) throw com.tiendaposs.common.ApiException.notFound("El asistente con IA no está disponible");
    }

    // ---------------- Recomendaciones (revisión humana) ----------------

    @GetMapping("/recommendations")
    @PreAuthorize("hasAnyAuthority('COSTOS_VER','PRODUCTOS_PRECIOS')")
    public List<RecommendationDto> list(@RequestParam(required = false) String status) {
        return recommendations.list(status);
    }

    @PostMapping("/recommendations/generate")
    @PreAuthorize("hasAuthority('COSTOS_VER')")
    public List<RecommendationDto> generate() {
        return recommendations.generate("REGLAS");
    }

    public record DecisionRequest(@Size(max = 300) String note) {
    }

    @PostMapping("/recommendations/{id}/approve")
    @PreAuthorize("hasAuthority('PRODUCTOS_PRECIOS')")
    public RecommendationDto approve(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return recommendations.approve(id, r == null ? null : r.note());
    }

    @PostMapping("/recommendations/{id}/dismiss")
    @PreAuthorize("hasAuthority('PRODUCTOS_PRECIOS')")
    public RecommendationDto dismiss(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return recommendations.dismiss(id, r == null ? null : r.note());
    }
}
