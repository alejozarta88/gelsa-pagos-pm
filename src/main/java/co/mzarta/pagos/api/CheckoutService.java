package co.mzarta.pagos.api;

import co.mzarta.pagos.domain.PaymentService;
import co.mzarta.pagos.domain.PaymentStatus;
import co.mzarta.pagos.metrics.IntegrityMetrics;
import co.mzarta.pagos.provider.PaymentProvider;
import co.mzarta.pagos.provider.PaymentProvider.ProviderException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Inicia un pago. Ninguna transacción queda abierta mientras se espera al proveedor. */
@Service
public class CheckoutService {

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final IntegrityMetrics metrics;

    public CheckoutService(JdbcClient db, TransactionTemplate tx, PaymentService payments,
                           PaymentProvider provider, IntegrityMetrics metrics) {
        this.db = db;
        this.tx = tx;
        this.payments = payments;
        this.provider = provider;
        this.metrics = metrics;
    }

    private record OrderRow(BigDecimal amount, String currency, String status) {}

    private record AttemptRow(UUID id, UUID orderId, String requestHash, String status, String redirectUrl) {}

    public ResponseEntity<Map<String, Object>> startPayment(UUID orderId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            return error(HttpStatus.BAD_REQUEST, "Falta la cabecera Idempotency-Key (máximo 80 caracteres)");
        }
        Optional<OrderRow> order = db.sql("SELECT amount, currency, status FROM orders WHERE id = ?")
                .param(orderId)
                .query((rs, i) -> new OrderRow(rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status")))
                .optional();
        if (order.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "La orden no existe");
        }
        OrderRow o = order.get();
        String hash = sha256(orderId + "|" + o.amount().stripTrailingZeros().toPlainString() + "|" + o.currency());

        Optional<AttemptRow> replay = findByKey(idempotencyKey);
        if (replay.isPresent()) {
            return replayResponse(replay.get(), orderId, hash);
        }
        if (!o.status().equals("PENDING_PAYMENT") && !o.status().equals("PAYMENT_FAILED")) {
            return error(HttpStatus.CONFLICT, "La orden está en estado " + o.status() + " y no admite pagos");
        }

        UUID attemptId = UUID.randomUUID();
        Optional<UUID> inserted;
        try {
            inserted = tx.execute(s -> {
                Optional<UUID> id = db.sql("""
                        INSERT INTO payment_attempts(id, order_id, idempotency_key, request_hash, provider, status, amount, currency)
                        VALUES (?, ?, ?, ?, 'psp-sim', 'CREATED', ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id""")
                        .param(attemptId).param(orderId).param(idempotencyKey).param(hash).param(o.amount()).param(o.currency())
                        .query(UUID.class).optional();
                id.ifPresent(payments::recordCreated);
                return id;
            });
        } catch (DuplicateKeyException e) {
            // uq_one_active_attempt: ya hay un intento activo para esta orden.
            metrics.blockedSecondAttempt();
            return error(HttpStatus.CONFLICT, "Ya hay un pago en curso o en verificación para esta orden");
        }
        if (inserted == null || inserted.isEmpty()) {
            // Otra petición con la misma clave ganó la carrera del INSERT.
            return replayResponse(findByKey(idempotencyKey).orElseThrow(), orderId, hash);
        }

        MDC.put("correlation_id", attemptId.toString());
        try {
            PaymentProvider.CreateResult r = provider.create(attemptId, o.amount(), o.currency(), attemptId.toString());
            payments.markPending(attemptId, r.providerPaymentId(), r.redirectUrl());
        } catch (ProviderException e) {
            // Un timeout no significa "falló": significa "no sé". No se reintenta a ciegas.
            metrics.providerUncertain();
            payments.apply(attemptId, PaymentStatus.UNKNOWN, "API", e.getMessage(), null);
        } finally {
            MDC.remove("correlation_id");
        }
        AttemptRow now = findByKey(idempotencyKey).orElseThrow();
        HttpStatus code = now.status().equals("UNKNOWN") ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(code).body(view(now));
    }

    private ResponseEntity<Map<String, Object>> replayResponse(AttemptRow a, UUID orderId, String hash) {
        if (!a.orderId().equals(orderId) || !a.requestHash().equals(hash)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "La Idempotency-Key ya se usó con otra petición");
        }
        metrics.idempotentReplay();
        HttpStatus code = a.status().equals("UNKNOWN") || a.status().equals("CREATED") ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(code).body(view(a));
    }

    private Optional<AttemptRow> findByKey(String key) {
        return db.sql("SELECT id, order_id, request_hash, status, redirect_url FROM payment_attempts WHERE idempotency_key = ?")
                .param(key)
                .query((rs, i) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("request_hash"), rs.getString("status"), rs.getString("redirect_url")))
                .optional();
    }

    private static Map<String, Object> view(AttemptRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attemptId", a.id());
        m.put("orderId", a.orderId());
        m.put("status", a.status());
        if (a.redirectUrl() != null) m.put("redirectUrl", a.redirectUrl());
        if (a.status().equals("UNKNOWN")) m.put("mensaje", "Estamos verificando tu pago. No intentes pagar de nuevo.");
        return m;
    }

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
