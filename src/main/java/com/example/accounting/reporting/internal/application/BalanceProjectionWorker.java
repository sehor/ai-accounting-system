package com.example.accounting.reporting.internal.application;

import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

/** Applies immutable balance events in small, retryable transactions. */
@Component
public class BalanceProjectionWorker {

    private final BalanceProjectionRepository repository;
    private final BalanceRebuildRepository rebuilds;
    private final boolean enabled;
    private final MeterRegistry metrics;

    public BalanceProjectionWorker(BalanceProjectionRepository repository, BalanceRebuildRepository rebuilds,
                                   @Value("${accounting.balance.worker-enabled:false}") boolean enabled,
                                   MeterRegistry metrics) {
        this.repository = repository;
        this.rebuilds = rebuilds;
        this.enabled = enabled;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${accounting.balance.worker-delay-ms:250}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            if (rebuilds.processNextJob()) {
                metrics.counter("accounting.balance.rebuilds.processed").increment();
                return;
            }
        } catch (RuntimeException exception) {
            rebuilds.failRunningJob();
            metrics.counter("accounting.balance.rebuilds.failures").increment();
            return;
        }
        try {
            if (repository.applyPendingBatch(200, 5000)) {
                metrics.counter("accounting.balance.events.applied").increment();
            }
        } catch (RuntimeException exception) {
            repository.recordFailure();
            metrics.counter("accounting.balance.projection.failures").increment();
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        if (enabled) {
            int removed = repository.cleanupAppliedEvents(OffsetDateTime.now().minusDays(90));
            if (removed > 0) {
                metrics.counter("accounting.balance.events.cleaned").increment(removed);
            }
        }
    }
}
