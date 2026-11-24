package co.mzarta.pagos.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrder(@NotBlank String customerRef,
                              @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
                              @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    private final JdbcClient db;
    private final CheckoutService checkout;

    public OrderController(JdbcClient db, CheckoutService checkout) {
        this.db = db;
        this.checkout = checkout;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrder r) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO orders(id, customer_ref, amount, currency, status) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT')")
                .param(id).param(r.customerRef()).param(r.amount()).param(r.currency()).update();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "status", "PENDING_PAYMENT"));
    }

    @PostMapping("/{id}/payments")
    public ResponseEntity<Map<String, Object>> pay(@PathVariable UUID id,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return checkout.startPayment(id, key);
    }

    /** Orden, intentos y línea de tiempo completa: reconstruye el ciclo de vida (AC-10). */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        List<Map<String, Object>> order = db.sql("SELECT id, customer_ref, amount, currency, status, created_at FROM orders WHERE id = ?")
                .param(id).query().listOfRows();
        if (order.isEmpty()) {
            return CheckoutService.error(HttpStatus.NOT_FOUND, "La orden no existe");
        }
        Map<String, Object> body = new LinkedHashMap<>(order.get(0));
        body.put("attempts", db.sql("SELECT id, status, provider_payment_id, reconcile_attempts, created_at FROM payment_attempts WHERE order_id = ? ORDER BY created_at")
                .param(id).query().listOfRows());
        body.put("timeline", db.sql("""
                SELECT t.at, t.attempt_id, t.from_status, t.to_status, t.source, t.detail
                  FROM payment_state_transitions t JOIN payment_attempts a ON a.id = t.attempt_id
                 WHERE a.order_id = ? ORDER BY t.at, t.id""").param(id).query().listOfRows());
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public List<Map<String, Object>> latest() {
        return db.sql("""
                SELECT o.id, o.status, o.amount, o.created_at,
                       (SELECT count(*) FROM payment_attempts a WHERE a.order_id = o.id) AS attempts
                  FROM orders o ORDER BY o.created_at DESC LIMIT 20""").query().listOfRows();
    }
}
