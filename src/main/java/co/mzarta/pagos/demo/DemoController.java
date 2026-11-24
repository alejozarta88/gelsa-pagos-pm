package co.mzarta.pagos.demo;

import co.mzarta.pagos.api.CheckoutService;
import co.mzarta.pagos.sim.ChaosSettings;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Lanza N compras concurrentes contra la pasarela hostil, con clientes que reintentan e impacientes. */
@RestController
public class DemoController {

    public record Run(int pagos, double duplicados, double desorden, double timeouts, double perdidos, double impacientes) {}

    private final CheckoutService checkout;
    private final ChaosSettings chaos;
    private final JdbcClient db;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DemoController(CheckoutService checkout, ChaosSettings chaos, JdbcClient db) {
        this.checkout = checkout;
        this.chaos = chaos;
        this.db = db;
    }

    @PostMapping("/api/demo/ejecutar")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Run r) {
        if (r.pagos() < 1 || r.pagos() > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "pagos debe estar entre 1 y 1000"));
        }
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("error", "Ya hay una corrida en curso"));
        }
        chaos.duplicateRate = clamp(r.duplicados());
        chaos.outOfOrderRate = clamp(r.desorden());
        chaos.timeoutRate = clamp(r.timeouts());
        chaos.lostRate = clamp(r.perdidos());
        chaos.autoComplete = true;
        Thread.ofVirtual().start(() -> {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < r.pagos(); i++) {
                    pool.submit(() -> oneCustomer(r.impacientes()));
                }
            } finally {
                running.set(false);
            }
        });
        return ResponseEntity.accepted().body(Map.of("mensaje", "Corrida iniciada", "pagos", r.pagos()));
    }

    private void oneCustomer(double impatientRate) {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(10_000, 500_000));
        db.sql("INSERT INTO orders(id, customer_ref, amount, currency, status) VALUES (?, ?, ?, 'COP', 'PENDING_PAYMENT')")
                .param(orderId).param("demo-" + orderId.toString().substring(0, 8)).param(amount).update();
        String key = UUID.randomUUID().toString();
        var first = checkout.startPayment(orderId, key);
        if (first.getStatusCode().value() == 202) {
            checkout.startPayment(orderId, key); // el cliente reintenta con la misma clave
            if (ThreadLocalRandom.current().nextDouble() < impatientRate) {
                checkout.startPayment(orderId, UUID.randomUUID().toString()); // abre otra pestaña: debe recibir 409
            }
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(0.5, v));
    }
}
