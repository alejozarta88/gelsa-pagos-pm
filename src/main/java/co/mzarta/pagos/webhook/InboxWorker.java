package co.mzarta.pagos.webhook;

import co.mzarta.pagos.PagosProperties;
import co.mzarta.pagos.domain.PaymentService;
import co.mzarta.pagos.domain.PaymentStatus;
import co.mzarta.pagos.provider.PaymentProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa el inbox. Reclama eventos con una "concesión" de 30 s (SKIP LOCKED) en una transacción corta,
 * consulta al proveedor fuera de toda transacción y aplica el cambio en otra transacción.
 * El webhook avisa; la API del proveedor confirma.
 */
@Component
public class InboxWorker {

    private static final Logger log = LoggerFactory.getLogger(InboxWorker.class);
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final PagosProperties props;

    public InboxWorker(JdbcClient db, TransactionTemplate tx, PaymentService payments, PaymentProvider provider, PagosProperties props) {
        this.db = db;
        this.tx = tx;
        this.payments = payments;
        this.provider = provider;
        this.props = props;
    }

    record Event(long id, String eventId, String reference, String providerPaymentId, String eventStatus, int attempts) {}

    @Scheduled(fixedDelayString = "${pagos.inbox-poll-ms}")
    public void poll() {
        List<Event> batch = tx.execute(s -> db.sql("""
                UPDATE payment_inbox SET attempts = attempts + 1, next_retry_at = now() + interval '30 seconds'
                 WHERE id IN (SELECT id FROM payment_inbox
                               WHERE status = 'RECEIVED' AND next_retry_at <= now()
                               ORDER BY id LIMIT 25 FOR UPDATE SKIP LOCKED)
                RETURNING id, provider_event_id, reference, provider_payment_id, event_status, attempts""")
                .query((rs, i) -> new Event(rs.getLong("id"), rs.getString("provider_event_id"), rs.getString("reference"),
                        rs.getString("provider_payment_id"), rs.getString("event_status"), rs.getInt("attempts")))
                .list());
        if (batch != null) batch.forEach(this::process);
    }

    void process(Event e) {
        MDC.put("correlation_id", e.reference());
        try {
            UUID attemptId = UUID.fromString(e.reference());
            BigDecimal expected = db.sql("SELECT amount FROM payment_attempts WHERE id = ?").param(attemptId)
                    .query(BigDecimal.class).optional()
                    .orElseThrow(() -> new IllegalStateException("referencia sin intento: " + e.reference()));
            Optional<PaymentProvider.StatusResult> truth = provider.findByReference(attemptId);
            if (truth.isPresent() && truth.get().amount().compareTo(expected) != 0) {
                dead(e, "el monto del proveedor no coincide con el intento");
                return;
            }
            PaymentStatus target = PaymentStatus.fromProvider(truth.map(PaymentProvider.StatusResult::status).orElse(e.eventStatus()));
            if (e.providerPaymentId() != null) payments.attachProviderId(attemptId, e.providerPaymentId());
            payments.apply(attemptId, target, "WEBHOOK", "evento " + e.eventId() + " (" + e.eventStatus() + ")", e.id());
        } catch (Exception ex) {
            retryLater(e, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            MDC.remove("correlation_id");
        }
    }

    private void retryLater(Event e, String error) {
        if (e.attempts() >= props.maxInboxAttempts()) {
            dead(e, error);
            return;
        }
        double backoff = Math.min(300, Math.pow(2, e.attempts()));
        db.sql("UPDATE payment_inbox SET last_error = ?, next_retry_at = now() + make_interval(secs => ?) WHERE id = ?")
                .param(error).param(backoff).param(e.id()).update();
        log.warn("evento reprogramado id={} intento={} error={}", e.eventId(), e.attempts(), error);
    }

    private void dead(Event e, String error) {
        db.sql("UPDATE payment_inbox SET status = 'DEAD', last_error = ?, processed_at = now() WHERE id = ?")
                .param(error).param(e.id()).update();
        log.error("ALERTA evento en DEAD id={} error={}", e.eventId(), error);
    }
}
