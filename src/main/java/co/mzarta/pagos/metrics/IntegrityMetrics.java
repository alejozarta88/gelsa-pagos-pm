package co.mzarta.pagos.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que el DAR estimó, aquí se mide. Los contadores de memoria se reinician con la aplicación (DT-08);
 * los que salen de SQL son la fuente de verdad.
 */
@RestController
@Component
public class IntegrityMetrics {

    private final JdbcClient db;
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong invalidSignatures = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong replays = new AtomicLong();
    private final AtomicLong uncertain = new AtomicLong();

    public IntegrityMetrics(JdbcClient db) {
        this.db = db;
    }

    public void duplicateWebhook() { duplicates.incrementAndGet(); }
    public void invalidSignature() { invalidSignatures.incrementAndGet(); }
    public void blockedSecondAttempt() { blocked.incrementAndGet(); }
    public void idempotentReplay() { replays.incrementAndGet(); }
    public void providerUncertain() { uncertain.incrementAndGet(); }

    @GetMapping("/api/metricas")
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ordenes", count("SELECT count(*) FROM orders"));
        m.put("intentos", count("SELECT count(*) FROM payment_attempts"));
        m.put("intentosPorEstado", db.sql("SELECT status, count(*) AS n FROM payment_attempts GROUP BY status ORDER BY status").query().listOfRows());
        m.put("webhooksRecibidos", count("SELECT count(*) FROM payment_inbox"));
        m.put("webhooksDuplicadosDescartados", duplicates.get());
        m.put("eventosObsoletosIgnorados", count("SELECT count(*) FROM payment_inbox WHERE status = 'IGNORED'"));
        m.put("eventosEnDead", count("SELECT count(*) FROM payment_inbox WHERE status = 'DEAD'"));
        m.put("eventosPendientes", count("SELECT count(*) FROM payment_inbox WHERE status = 'RECEIVED'"));
        m.put("firmasInvalidas", invalidSignatures.get());
        m.put("timeoutsDelProveedor", uncertain.get());
        m.put("reintentosIdempotentes", replays.get());
        m.put("segundosIntentosBloqueados", blocked.get());
        m.put("resueltosPorConciliacion", count("SELECT count(*) FROM payment_state_transitions WHERE source = 'RECONCILER'"));
        m.put("reembolsosRequeridos", count("SELECT count(*) FROM payment_attempts WHERE status = 'REFUND_REQUIRED'"));
        m.put("outboxPendiente", count("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'"));
        // Cobro duplicado: una orden con más de un cobro aprobado del lado del proveedor.
        m.put("cobrosDuplicados", count("""
                SELECT count(*) FROM (
                  SELECT a.order_id FROM psp_sim_payments s JOIN payment_attempts a ON a.id::text = s.reference
                   WHERE s.status = 'APPROVED' GROUP BY a.order_id HAVING count(*) > 1) x"""));
        // Inconsistencia: el estado de la orden no corresponde a sus intentos.
        m.put("ordenesInconsistentes", count("""
                SELECT count(*) FROM orders o
                 WHERE (o.status = 'PAID' AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.order_id = o.id AND a.status = 'APPROVED'))
                    OR (o.status <> 'PAID' AND EXISTS (SELECT 1 FROM payment_attempts a WHERE a.order_id = o.id AND a.status = 'APPROVED'))"""));
        // Divergencia con el proveedor: ya resuelto aquí pero distinto allá.
        m.put("divergenciasConProveedor", count("""
                SELECT count(*) FROM payment_attempts a JOIN psp_sim_payments s ON s.reference = a.id::text
                 WHERE a.status IN ('APPROVED','DECLINED') AND s.status IN ('APPROVED','DECLINED') AND a.status <> s.status"""));
        return m;
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
