package co.mzarta.pagos;

import static org.assertj.core.api.Assertions.assertThat;

import co.mzarta.pagos.sim.ChaosSettings;
import co.mzarta.pagos.sim.PspSimulator;
import co.mzarta.pagos.webhook.WebhookSigner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pruebas de las garantías contra PostgreSQL real. Cada prueba nombra el criterio de aceptación
 * de specs/01-especificacion.md que protege.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pagos.webhook-secret=secreto-de-prueba",
        "psp-sim.auto-complete=false",
        "psp-sim.timeout-delay-ms=1500",
        "pagos.provider-timeout=1s",
        "pagos.unknown-recheck=1s",
        "pagos.pending-recheck=1s",
        "pagos.reconcile-poll-ms=300",
        "pagos.inbox-poll-ms=100"})
class IntegridadTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;
    @Autowired PspSimulator sim;
    @Autowired ChaosSettings chaos;
    @Autowired WebhookSigner signer;

    @AfterEach
    void sinCaos() {
        chaos.timeoutRate = 0;
    }

    // ---------- ayudas

    private UUID newOrder() {
        Map<?, ?> r = http.postForObject("/api/orders", Map.of("customerRef", "c-1", "amount", 150000, "currency", "COP"), Map.class);
        return UUID.fromString((String) r.get("id"));
    }

    private ResponseEntity<Map> pay(UUID orderId, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return http.exchange("/api/orders/" + orderId + "/payments", HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
    }

    private ResponseEntity<Map> webhook(String body, String signature) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        h.set("X-Psp-Timestamp", ts);
        h.set("X-Psp-Signature", signature != null ? signature : signer.sign(ts, body));
        return http.postForEntity("/webhooks/psp", new HttpEntity<>(body, h), Map.class);
    }

    private String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE id = ?").param(attemptId).query(String.class).single();
    }

    private long count(String sql, Object... params) {
        var spec = db.sql(sql);
        for (Object p : params) spec = spec.param(p);
        return spec.query(Long.class).single();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("La condición no se cumplió en 10 s");
    }

    private static <T> List<T> inParallel(int n, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        }
    }

    // ---------- pruebas

    @Test
    void ac01_laMismaClaveDevuelveElMismoIntento() {
        UUID order = newOrder();
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = pay(order, key);
        ResponseEntity<Map> b = pay(order, key);
        assertThat(a.getStatusCode().value()).isEqualTo(201);
        assertThat(b.getStatusCode().value()).isEqualTo(200);
        assertThat(b.getBody().get("attemptId")).isEqualTo(a.getBody().get("attemptId"));
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", order)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM psp_sim_payments s JOIN payment_attempts a ON a.id::text = s.reference WHERE a.order_id = ?", order)).isEqualTo(1);
    }

    @Test
    void ac02_dosIntentosConClavesDistintasEnParaleloSoloCreaUno() throws Exception {
        UUID order = newOrder();
        List<Integer> codes = inParallel(2, () -> pay(order, UUID.randomUUID().toString()).getStatusCode().value());
        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", order)).isEqualTo(1);
    }

    @Test
    void ac04_webhookDuplicadoEnParaleloTieneUnSoloEfecto() throws Exception {
        UUID order = newOrder();
        UUID attempt = UUID.fromString((String) pay(order, UUID.randomUUID().toString()).getBody().get("attemptId"));
        sim.forceStatus(attempt.toString(), "APPROVED");
        String pid = sim.paymentIdFor(attempt.toString()).orElseThrow();
        String body = sim.event(pid, attempt.toString(), "APPROVED", "150000.0000");

        List<Integer> codes = inParallel(10, () -> webhook(body, null).getStatusCode().value());

        assertThat(codes).allMatch(c -> c == 200);
        await(() -> status(attempt).equals("APPROVED"));
        Thread.sleep(500); // margen para que un segundo efecto, si existiera, aparezca
        assertThat(count("SELECT count(*) FROM payment_inbox WHERE reference = ?", attempt.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_state_transitions WHERE attempt_id = ? AND to_status = 'APPROVED'", attempt)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentAPPROVED'", attempt)).isEqualTo(1);
        assertThat(db.sql("SELECT status FROM orders WHERE id = ?").param(order).query(String.class).single()).isEqualTo("PAID");
    }

    @Test
    void ac05_unEventoTardioNoRetrocedeElEstado() throws Exception {
        UUID order = newOrder();
        UUID attempt = UUID.fromString((String) pay(order, UUID.randomUUID().toString()).getBody().get("attemptId"));
        sim.forceStatus(attempt.toString(), "APPROVED");
        String pid = sim.paymentIdFor(attempt.toString()).orElseThrow();
        webhook(sim.event(pid, attempt.toString(), "APPROVED", "150000.0000"), null);
        await(() -> status(attempt).equals("APPROVED"));

        webhook(sim.event(pid, attempt.toString(), "PENDING", "150000.0000"), null);
        await(() -> count("SELECT count(*) FROM payment_inbox WHERE reference = ? AND status = 'IGNORED'", attempt.toString()) == 1);
        assertThat(status(attempt)).isEqualTo("APPROVED");
    }

    @Test
    void ac06_timeoutQuedaDesconocidoYLaConciliacionLoResuelve() throws Exception {
        chaos.timeoutRate = 1.0;
        UUID order = newOrder();
        ResponseEntity<Map> r = pay(order, UUID.randomUUID().toString());
        chaos.timeoutRate = 0;
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        assertThat(r.getBody().get("status")).isEqualTo("UNKNOWN");
        UUID attempt = UUID.fromString((String) r.getBody().get("attemptId"));

        // Un segundo intento con otra clave queda bloqueado mientras el primero está en verificación.
        assertThat(pay(order, UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(409);

        sim.forceStatus(attempt.toString(), "APPROVED"); // el proveedor sí había cobrado
        await(() -> status(attempt).equals("APPROVED"));
        assertThat(count("SELECT count(*) FROM payment_state_transitions WHERE attempt_id = ? AND source = 'RECONCILER'", attempt)).isGreaterThanOrEqualTo(1);
        assertThat(db.sql("SELECT status FROM orders WHERE id = ?").param(order).query(String.class).single()).isEqualTo("PAID");
    }

    @Test
    void ac07_firmaInvalidaNoTieneEfecto() {
        long before = count("SELECT count(*) FROM payment_inbox");
        String body = sim.event("psp_x", UUID.randomUUID().toString(), "APPROVED", "1.00");
        assertThat(webhook(body, "firma-falsa").getStatusCode().value()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM payment_inbox")).isEqualTo(before);
    }
}
