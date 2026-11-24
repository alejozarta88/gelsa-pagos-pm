package co.mzarta.pagos.provider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Puerto hacia la pasarela. El dominio no conoce HTTP. */
public interface PaymentProvider {

    record CreateResult(String providerPaymentId, String redirectUrl) {}

    record StatusResult(String providerPaymentId, String status, BigDecimal amount) {}

    /** Puede lanzar ProviderException: en ese caso el resultado es desconocido, no fallido. */
    CreateResult create(UUID reference, BigDecimal amount, String currency, String idempotencyKey);

    /** Vacío significa que el proveedor no conoce la referencia: no hubo cobro. */
    Optional<StatusResult> findByReference(UUID reference);

    class ProviderException extends RuntimeException {
        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
