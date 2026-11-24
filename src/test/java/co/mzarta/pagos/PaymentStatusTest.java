package co.mzarta.pagos;

import static co.mzarta.pagos.domain.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import co.mzarta.pagos.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void unEstadoFinalNoRetrocede() {
        for (PaymentStatus to : new PaymentStatus[]{CREATED, PENDING, UNKNOWN}) {
            assertThat(APPROVED.canTransitionTo(to)).as("APPROVED -> " + to).isFalse();
            assertThat(DECLINED.canTransitionTo(to)).as("DECLINED -> " + to).isFalse();
            assertThat(EXPIRED.canTransitionTo(to)).as("EXPIRED -> " + to).isFalse();
        }
    }

    @Test
    void unAprobadoSoloPuedeIrAReembolso() {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(APPROVED.canTransitionTo(to)).as("APPROVED -> " + to).isEqualTo(to == REFUND_REQUIRED);
        }
    }

    @Test
    void elDesconocidoSeResuelveEnCualquierSentido() {
        assertThat(UNKNOWN.canTransitionTo(APPROVED)).isTrue();
        assertThat(UNKNOWN.canTransitionTo(DECLINED)).isTrue();
        assertThat(UNKNOWN.canTransitionTo(EXPIRED)).isTrue();
        assertThat(UNKNOWN.canTransitionTo(PENDING)).isTrue();
    }

    @Test
    void ningunEstadoSeRepiteASiMismo() {
        for (PaymentStatus s : PaymentStatus.values()) {
            assertThat(s.canTransitionTo(s)).as(s.name()).isFalse();
        }
    }
}
