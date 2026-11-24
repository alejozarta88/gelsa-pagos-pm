package co.mzarta.pagos.provider;

import co.mzarta.pagos.PagosProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Cliente HTTP del proveedor con timeouts explícitos.
 * Crear un pago NO se reintenta tras un timeout: se deja UNKNOWN y lo resuelve la conciliación.
 * Consultar el estado sí se reintenta (es una lectura).
 */
@Component
public class HttpPaymentProvider implements PaymentProvider {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper json;
    private final PagosProperties props;
    private final Environment env;

    public HttpPaymentProvider(ObjectMapper json, PagosProperties props, Environment env) {
        this.json = json;
        this.props = props;
        this.env = env;
    }

    private String baseUrl() {
        // Se resuelve en cada llamada para tomar el puerto real del servidor (pruebas con puerto aleatorio).
        return env.getRequiredProperty("pagos.provider-base-url");
    }

    @Override
    public CreateResult create(UUID reference, BigDecimal amount, String currency, String idempotencyKey) {
        try {
            String body = json.writeValueAsString(Map.of("reference", reference.toString(),
                    "amount", amount.toPlainString(), "currency", currency));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/payments"))
                    .timeout(props.providerTimeout())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new ProviderException("HTTP " + res.statusCode() + " al crear el pago", null);
            }
            JsonNode n = json.readTree(res.body());
            return new CreateResult(n.path("id").asText(), n.path("redirect_url").asText());
        } catch (HttpTimeoutException e) {
            throw new ProviderException("TIMEOUT al crear el pago", e);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ProviderException("Fallo de comunicación al crear el pago: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<StatusResult> findByReference(UUID reference) {
        ProviderException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String url = baseUrl() + "/payments?reference=" + URLEncoder.encode(reference.toString(), StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 404) return Optional.empty();
                if (res.statusCode() / 100 != 2) throw new ProviderException("HTTP " + res.statusCode() + " al consultar", null);
                JsonNode n = json.readTree(res.body());
                return Optional.of(new StatusResult(n.path("id").asText(), n.path("status").asText(),
                        new BigDecimal(n.path("amount").asText())));
            } catch (ProviderException e) {
                last = e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                last = new ProviderException("Fallo al consultar el estado: " + e.getClass().getSimpleName(), e);
            }
            sleep((200L << attempt) + ThreadLocalRandom.current().nextLong(100)); // backoff exponencial con jitter
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
