package co.mzarta.pagos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("pagos")
public record PagosProperties(
        @NotBlank String webhookSecret,
        Duration providerTimeout,
        Duration pendingRecheck,
        Duration unknownRecheck,
        int maxInboxAttempts,
        int maxReconcileAttempts) {
}
