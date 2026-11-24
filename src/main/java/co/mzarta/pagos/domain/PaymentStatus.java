package co.mzarta.pagos.domain;

import java.util.EnumSet;
import java.util.Set;

/** Máquina de estados del intento de pago. Cada destino declara desde qué estados se puede llegar. */
public enum PaymentStatus {
    CREATED, PENDING, UNKNOWN, APPROVED, DECLINED, EXPIRED, REFUND_REQUIRED, REFUNDED;

    public Set<PaymentStatus> allowedFrom() {
        return switch (this) {
            case CREATED -> EnumSet.noneOf(PaymentStatus.class);
            case PENDING -> EnumSet.of(CREATED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(CREATED, PENDING);
            case APPROVED, DECLINED, EXPIRED -> EnumSet.of(CREATED, PENDING, UNKNOWN);
            // Cobro que no debe quedarse: orden ya pagada o cancelada, o aprobado tardío sobre un intento cerrado.
            case REFUND_REQUIRED -> EnumSet.of(CREATED, PENDING, UNKNOWN, APPROVED, DECLINED, EXPIRED);
            case REFUNDED -> EnumSet.of(REFUND_REQUIRED);
        };
    }

    public boolean canTransitionTo(PaymentStatus to) {
        return to.allowedFrom().contains(this);
    }

    public boolean isActive() {
        return this == CREATED || this == PENDING || this == UNKNOWN;
    }

    public static PaymentStatus fromProvider(String providerStatus) {
        return switch (providerStatus) {
            case "APPROVED" -> APPROVED;
            case "DECLINED" -> DECLINED;
            case "PENDING" -> PENDING;
            default -> throw new IllegalArgumentException("Estado de proveedor desconocido: " + providerStatus);
        };
    }
}
