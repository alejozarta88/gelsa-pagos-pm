package co.mzarta.pagos.sim;

import co.mzarta.pagos.webhook.WebhookSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * Pasarela de pagos simulada, con el comportamiento hostil que describe el enunciado:
 * responde tarde, entrega webhooks duplicados, fuera de orden o nunca.
 * Vive en la misma aplicación solo para que la demo sea autocontenida (DT-03).
 */
@RestController
@RequestMapping("/psp-sim")
public class PspSimulator {

    private static final Logger log = LoggerFactory.getLogger(PspSimulator.class);
    private final JdbcClient db;
    private final ChaosSettings chaos;
    private final WebhookSigner signer;
    private final ObjectMapper json;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());

    public PspSimulator(JdbcClient db, ChaosSettings chaos, WebhookSigner signer, ObjectMapper json, Environment env) {
        this.db = db;
        this.chaos = chaos;
        this.signer = signer;
        this.json = json;
        this.env = env;
    }

    public record CreateRequest(String reference, BigDecimal amount, String currency) {}

    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader("Idempotency-Key") String key, @RequestBody CreateRequest r)
            throws InterruptedException {
        String id = "psp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int inserted = db.sql("INSERT INTO psp_sim_payments(id, reference, idempotency_key, amount, status) VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT (idempotency_key) DO NOTHING")
                .param(id).param(r.reference()).param(key).param(r.amount()).update();
        if (inserted == 0) {
            id = db.sql("SELECT id FROM psp_sim_payments WHERE idempotency_key = ?").param(key).query(String.class).single();
        } else if (chaos.autoComplete) {
            String pid = id;
            scheduler.schedule(() -> complete(pid), 150 + ThreadLocalRandom.current().nextInt(900), TimeUnit.MILLISECONDS);
        }
        if (ThreadLocalRandom.current().nextDouble() < chaos.timeoutRate) {
            // El cobro ya quedó registrado del lado del proveedor; el cliente no se va a enterar a tiempo.
            Thread.sleep(chaos.timeoutDelayMs);
        }
        return ResponseEntity.status(201).body(Map.of("id", id, "status", "PENDING", "redirect_url", "https://psp-sim.example/checkout/" + id));
    }

    @GetMapping("/payments")
    public ResponseEntity<Map<String, Object>> find(@RequestParam String reference) {
        return db.sql("SELECT id, status, amount FROM psp_sim_payments WHERE reference = ? ORDER BY created_at LIMIT 1")
                .param(reference).query().listOfRows().stream().findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** El cliente terminó en el portal: se decide el resultado y se notifican los webhooks con caos. */
    void complete(String paymentId) {
        try {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            String finalStatus = rnd.nextDouble() < chaos.approveRate ? "APPROVED" : "DECLINED";
            db.sql("UPDATE psp_sim_payments SET status = ? WHERE id = ?").param(finalStatus).param(paymentId).update();
            if (rnd.nextDouble() < chaos.lostRate) {
                log.info("psp-sim: webhooks perdidos para {}", paymentId);
                return;
            }
            String reference = db.sql("SELECT reference FROM psp_sim_payments WHERE id = ?").param(paymentId).query(String.class).single();
            String amount = db.sql("SELECT amount::text FROM psp_sim_payments WHERE id = ?").param(paymentId).query(String.class).single();
            List<String> order = rnd.nextDouble() < chaos.outOfOrderRate
                    ? List.of(finalStatus, "PENDING")   // el PENDING llega después del resultado final
                    : List.of("PENDING", finalStatus);
            long delay = 0;
            for (String status : order) {
                String body = event(paymentId, reference, status, amount);
                int copies = rnd.nextDouble() < chaos.duplicateRate ? 2 : 1;
                for (int c = 0; c < copies; c++) {
                    scheduler.schedule(() -> send(body), delay, TimeUnit.MILLISECONDS);
                }
                delay += 120;
            }
        } catch (Exception e) {
            log.warn("psp-sim: fallo completando {}: {}", paymentId, e.getMessage());
        }
    }

    /** Solo para pruebas: fija el estado que reporta el proveedor. */
    public void forceStatus(String reference, String status) {
        db.sql("UPDATE psp_sim_payments SET status = ? WHERE reference = ?").param(status).param(reference).update();
    }

    public Optional<String> paymentIdFor(String reference) {
        return db.sql("SELECT id FROM psp_sim_payments WHERE reference = ?").param(reference).query(String.class).optional();
    }

    public String event(String paymentId, String reference, String status, String amount) {
        try {
            return json.writeValueAsString(Map.of(
                    "event_id", "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    "payment_id", paymentId, "reference", reference, "status", status,
                    "amount", amount, "occurred_at", Instant.now().toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void send(String body) {
        try {
            String ts = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest req = HttpRequest.newBuilder(URI.create(env.getRequiredProperty("pagos.self-base-url") + "/webhooks/psp"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Psp-Timestamp", ts)
                    .header("X-Psp-Signature", signer.sign(ts, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("psp-sim: no se pudo entregar un webhook: {}", e.getMessage());
        }
    }
}
