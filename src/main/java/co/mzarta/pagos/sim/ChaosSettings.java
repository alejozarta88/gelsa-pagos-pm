package co.mzarta.pagos.sim;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Perillas de caos del proveedor simulado. Se cambian en caliente desde la demo. */
@Component
public class ChaosSettings {
    public volatile double duplicateRate = 0.05;
    public volatile double outOfOrderRate = 0.05;
    public volatile double timeoutRate = 0.02;
    public volatile double lostRate = 0.01;
    public volatile double approveRate = 0.9;
    public volatile boolean autoComplete;
    public volatile long timeoutDelayMs;

    public ChaosSettings(@Value("${psp-sim.auto-complete}") boolean autoComplete,
                         @Value("${psp-sim.timeout-delay-ms}") long timeoutDelayMs) {
        this.autoComplete = autoComplete;
        this.timeoutDelayMs = timeoutDelayMs;
    }
}
