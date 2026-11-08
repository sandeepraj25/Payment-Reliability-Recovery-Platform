package com.payguard.worker;

import com.payguard.outbox.OutboxStore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Operator API for processing history and dead letters (ADMIN only, see SecurityConfig). */
@RestController
public class ProcessingController {

    private final ProcessingStore store;
    private final OutboxStore outbox;
    private final DeadLetterService deadLetters;

    public ProcessingController(ProcessingStore store, OutboxStore outbox, DeadLetterService deadLetters) {
        this.store = store;
        this.outbox = outbox;
        this.deadLetters = deadLetters;
    }

    public record ReplayRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/api/webhook-events/{id}/attempts")
    public List<ProcessingStore.Attempt> attempts(@PathVariable long id) {
        return store.attempts(id);
    }

    @GetMapping("/api/webhook-events/{id}/outbox")
    public List<OutboxStore.OutboxRow> outboxRows(@PathVariable long id) {
        return outbox.forWebhookEvent(id);
    }

    @GetMapping("/api/dead-letters")
    public Map<String, Object> deadLetters(@RequestParam(defaultValue = "100") int limit) {
        return Map.of(
                "events", deadLetters.list(Math.max(1, Math.min(limit, 500))),
                "brokerDlqDepth", deadLetters.brokerDlqDepth());
    }

    @PostMapping("/api/dead-letters/{webhookEventId}/replay")
    public Map<String, Object> replay(@PathVariable long webhookEventId,
                                      @jakarta.validation.Valid @RequestBody ReplayRequest request, Principal principal) {
        return deadLetters.replay(webhookEventId, principal.getName(), request.reason());
    }
}
