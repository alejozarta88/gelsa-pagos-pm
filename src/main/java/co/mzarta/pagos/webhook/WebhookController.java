package co.mzarta.pagos.webhook;

import co.mzarta.pagos.metrics.IntegrityMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recibe, valida y guarda. No ejecuta lógica de negocio ni llama a terceros:
 * responde en milisegundos y deja el trabajo a InboxWorker.
 */
@RestController
public class WebhookController {

    private final WebhookSigner signer;
    private final ObjectMapper json;
    private final JdbcClient db;
    private final IntegrityMetrics metrics;

    public WebhookController(WebhookSigner signer, ObjectMapper json, JdbcClient db, IntegrityMetrics metrics) {
        this.signer = signer;
        this.json = json;
        this.db = db;
        this.metrics = metrics;
    }

    @PostMapping("/webhooks/psp")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String body,
                                                       @RequestHeader(value = "X-Psp-Timestamp", required = false) String ts,
                                                       @RequestHeader(value = "X-Psp-Signature", required = false) String sig) {
        if (!signer.verify(ts, body, sig)) {
            metrics.invalidSignature();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "firma inválida"));
        }
        JsonNode n;
        try {
            n = json.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "JSON inválido"));
        }
        String eventId = n.path("event_id").asText("");
        String reference = n.path("reference").asText("");
        String status = n.path("status").asText("");
        if (eventId.isEmpty() || reference.isEmpty() || status.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "faltan event_id, reference o status"));
        }
        int rows = db.sql("""
                INSERT INTO payment_inbox(provider, provider_event_id, provider_payment_id, reference, event_status, payload)
                VALUES ('psp-sim', ?, ?, ?, ?, cast(? as jsonb))
                ON CONFLICT (provider, provider_event_id) DO NOTHING""")
                .param(eventId).param(n.path("payment_id").asText(null)).param(reference).param(status).param(body)
                .update();
        if (rows == 0) {
            metrics.duplicateWebhook();
        }
        return ResponseEntity.ok(Map.of("received", true, "duplicate", rows == 0));
    }
}
