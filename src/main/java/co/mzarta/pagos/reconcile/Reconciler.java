package co.mzarta.pagos.reconcile;

import co.mzarta.pagos.PagosProperties;
import co.mzarta.pagos.domain.PaymentService;
import co.mzarta.pagos.domain.PaymentStatus;
import co.mzarta.pagos.provider.PaymentProvider;
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

/** Resuelve pagos UNKNOWN y PENDING vencidos preguntándole al proveedor. Nunca crea cobros. */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final PagosProperties props;

    public Reconciler(JdbcClient db, TransactionTemplate tx, PaymentService payments, PaymentProvider provider, PagosProperties props) {
        this.db = db;
        this.tx = tx;
        this.payments = payments;
        this.provider = provider;
        this.props = props;
    }

    record Due(UUID id, PaymentStatus status, int attempts) {}

    @Scheduled(fixedDelayString = "${pagos.reconcile-poll-ms}")
    public void run() {
        List<Due> due = tx.execute(s -> db.sql("""
                UPDATE payment_attempts
                   SET reconcile_attempts = reconcile_attempts + 1,
                       next_check_at = now() + make_interval(secs => least(7200, 2 * power(2, reconcile_attempts)))
                 WHERE id IN (SELECT id FROM payment_attempts
                               WHERE status IN ('PENDING','UNKNOWN') AND next_check_at <= now()
                               ORDER BY next_check_at LIMIT 50 FOR UPDATE SKIP LOCKED)
                RETURNING id, status, reconcile_attempts""")
                .query((rs, i) -> new Due(rs.getObject("id", UUID.class), PaymentStatus.valueOf(rs.getString("status")),
                        rs.getInt("reconcile_attempts")))
                .list());
        if (due != null) due.forEach(this::reconcile);
    }

    void reconcile(Due d) {
        MDC.put("correlation_id", d.id().toString());
        try {
            Optional<PaymentProvider.StatusResult> st = provider.findByReference(d.id());
            if (st.isEmpty()) {
                if (d.status() == PaymentStatus.UNKNOWN) {
                    payments.apply(d.id(), PaymentStatus.EXPIRED, "RECONCILER", "el proveedor no conoce la referencia: no hubo cobro", null);
                }
            } else {
                PaymentStatus target = PaymentStatus.fromProvider(st.get().status());
                payments.attachProviderId(d.id(), st.get().providerPaymentId());
                if (target != d.status()) {
                    payments.apply(d.id(), target, "RECONCILER", "estado confirmado con el proveedor: " + st.get().status(), null);
                }
            }
            if (d.attempts() >= props.maxReconcileAttempts()) {
                log.error("ALERTA pago sin resolver tras {} intentos de conciliación attempt={}: revisión manual", d.attempts(), d.id());
            }
        } catch (Exception ex) {
            log.warn("conciliación aplazada attempt={} error={}", d.id(), ex.getMessage());
        } finally {
            MDC.remove("correlation_id");
        }
    }
}
