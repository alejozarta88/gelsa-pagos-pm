package co.mzarta.pagos.domain;

import static co.mzarta.pagos.domain.PaymentStatus.*;

import co.mzarta.pagos.PagosProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Único punto que cambia el estado de un intento de pago.
 * Estado, transición, orden y evento de outbox se escriben en una sola transacción.
 */
@Service
public class PaymentService {

    public enum Result { APPLIED, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final JdbcClient db;
    private final PagosProperties props;

    public PaymentService(JdbcClient db, PagosProperties props) {
        this.db = db;
        this.props = props;
    }

    private record Current(UUID orderId, PaymentStatus status, int version) {}

    @Transactional
    public void recordCreated(UUID attemptId) {
        db.sql("INSERT INTO payment_state_transitions(attempt_id, from_status, to_status, source, detail) VALUES (?, NULL, 'CREATED', 'API', 'intento registrado')")
                .param(attemptId).update();
    }

    /** Guarda los datos del proveedor y pasa a PENDING si todavía corresponde. */
    @Transactional
    public Result markPending(UUID attemptId, String providerPaymentId, String redirectUrl) {
        db.sql("UPDATE payment_attempts SET provider_payment_id = coalesce(provider_payment_id, ?), redirect_url = coalesce(redirect_url, ?) WHERE id = ?")
                .param(providerPaymentId).param(redirectUrl).param(attemptId).update();
        return apply(attemptId, PENDING, "API", "el proveedor aceptó el pago", null);
    }

    @Transactional
    public void attachProviderId(UUID attemptId, String providerPaymentId) {
        db.sql("UPDATE payment_attempts SET provider_payment_id = coalesce(provider_payment_id, ?) WHERE id = ?")
                .param(providerPaymentId).param(attemptId).update();
    }

    /**
     * Aplica una transición si la máquina de estados la permite.
     * Bloquea la fila de la orden para serializar todo lo que la toca (corto y por orden).
     */
    @Transactional
    public Result apply(UUID attemptId, PaymentStatus requested, String source, String detail, Long inboxId) {
        Current cur = db.sql("SELECT order_id, status, version FROM payment_attempts WHERE id = ?")
                .param(attemptId)
                .query((rs, i) -> new Current(rs.getObject("order_id", UUID.class),
                        PaymentStatus.valueOf(rs.getString("status")), rs.getInt("version")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No existe el intento " + attemptId));

        String orderStatus = db.sql("SELECT status FROM orders WHERE id = ? FOR UPDATE")
                .param(cur.orderId()).query(String.class).single();

        if (cur.status() == requested) {
            return ignore(inboxId, attemptId, "estado repetido " + requested);
        }

        PaymentStatus to = requested;
        if (requested == APPROVED) {
            long otherApproved = db.sql("SELECT count(*) FROM payment_attempts WHERE order_id = ? AND status = 'APPROVED' AND id <> ?")
                    .param(cur.orderId()).param(attemptId).query(Long.class).single();
            boolean orderOpen = orderStatus.equals("PENDING_PAYMENT") || orderStatus.equals("PAYMENT_FAILED");
            boolean attemptClosed = cur.status() == DECLINED || cur.status() == EXPIRED;
            if (otherApproved > 0 || !orderOpen || attemptClosed) {
                to = REFUND_REQUIRED;
                detail = detail + " | aprobado que no puede quedarse (orden " + orderStatus + ", intento " + cur.status() + ")";
            }
        }

        if (!cur.status().canTransitionTo(to)) {
            return ignore(inboxId, attemptId, "transición no permitida " + cur.status() + " -> " + to);
        }

        int rows = db.sql("""
                UPDATE payment_attempts
                   SET status = :to, version = version + 1, updated_at = now(),
                       next_check_at = CASE WHEN :to = 'PENDING' THEN now() + make_interval(secs => :pending)
                                            WHEN :to = 'UNKNOWN' THEN now() + make_interval(secs => :unknown)
                                            ELSE NULL END
                 WHERE id = :id AND version = :version""")
                .param("to", to.name())
                .param("pending", (double) props.pendingRecheck().toSeconds())
                .param("unknown", (double) props.unknownRecheck().toSeconds())
                .param("id", attemptId)
                .param("version", cur.version())
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException("El intento " + attemptId + " cambió mientras se procesaba");
        }

        db.sql("INSERT INTO payment_state_transitions(attempt_id, from_status, to_status, source, detail) VALUES (?, ?, ?, ?, ?)")
                .param(attemptId).param(cur.status().name()).param(to.name()).param(source).param(detail).update();

        String orderTarget = switch (to) {
            case APPROVED -> "PAID";
            case DECLINED, EXPIRED -> "PAYMENT_FAILED";
            default -> null;
        };
        if (orderTarget != null) {
            db.sql("UPDATE orders SET status = ?, version = version + 1, updated_at = now() WHERE id = ? AND status IN ('PENDING_PAYMENT','PAYMENT_FAILED')")
                    .param(orderTarget).param(cur.orderId()).update();
        }

        db.sql("INSERT INTO outbox_events(id, aggregate_id, event_type, payload) VALUES (?, ?, ?, cast(? as jsonb))")
                .param(UUID.randomUUID()).param(attemptId).param("Payment" + to.name())
                .param("{\"attemptId\":\"" + attemptId + "\",\"orderId\":\"" + cur.orderId() + "\",\"from\":\"" + cur.status() + "\",\"to\":\"" + to + "\"}")
                .update();

        if (inboxId != null) {
            markInbox(inboxId, "PROCESSED", null);
        }
        if (to == REFUND_REQUIRED) {
            log.error("ALERTA cobro a reembolsar attempt={} order={} detalle={}", attemptId, cur.orderId(), detail);
        } else {
            log.info("transicion attempt={} order={} {}->{} source={}", attemptId, cur.orderId(), cur.status(), to, source);
        }
        return Result.APPLIED;
    }

    private Result ignore(Long inboxId, UUID attemptId, String reason) {
        if (inboxId != null) {
            markInbox(inboxId, "IGNORED", reason);
        }
        log.info("transicion ignorada attempt={} motivo={}", attemptId, reason);
        return Result.IGNORED;
    }

    private void markInbox(long inboxId, String status, String reason) {
        db.sql("UPDATE payment_inbox SET status = ?, last_error = ?, processed_at = now() WHERE id = ?")
                .param(status).param(reason).param(inboxId).update();
    }
}
