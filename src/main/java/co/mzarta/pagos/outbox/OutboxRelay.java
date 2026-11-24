package co.mzarta.pagos.outbox;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica los eventos del outbox. En el PMV "publicar" es registrarlo en el log (DT-02).
 * El contrato ya existe: cambiar el destino a un broker no toca el dominio.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient db;

    public OutboxRelay(JdbcClient db) {
        this.db = db;
    }

    @Scheduled(fixedDelayString = "${pagos.outbox-poll-ms}")
    @Transactional
    public void publish() {
        List<Map<String, Object>> batch = db.sql("""
                SELECT id, event_type, payload::text AS payload FROM outbox_events
                 WHERE status = 'PENDING' ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED""").query().listOfRows();
        for (Map<String, Object> ev : batch) {
            log.info("evento publicado tipo={} id={} payload={}", ev.get("event_type"), ev.get("id"), ev.get("payload"));
            db.sql("UPDATE outbox_events SET status = 'SENT', sent_at = now(), attempts = attempts + 1 WHERE id = ?")
                    .param(ev.get("id")).update();
        }
    }
}
