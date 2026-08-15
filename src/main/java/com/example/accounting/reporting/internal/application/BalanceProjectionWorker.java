package com.example.accounting.reporting.internal.application;

import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/** Applies immutable balance events in small, retryable transactions. */
@Component
public class BalanceProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(BalanceProjectionWorker.class);

    private final BalanceProjectionRepository repository;
    private final BalanceRebuildRepository rebuilds;
    private final boolean enabled;
    private final MeterRegistry metrics;
    private final int propagationPeriodBatchSize;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private final boolean legacyTailRebuildEnabled;
    private final AtomicLong cleanupPending = new AtomicLong();
    private final AtomicLong cleanupOldestAgeSeconds = new AtomicLong();
    private final AtomicLong dirtyPeriods = new AtomicLong();
    private final AtomicLong dirtyOldestAgeSeconds = new AtomicLong();

    public BalanceProjectionWorker(BalanceProjectionRepository repository, BalanceRebuildRepository rebuilds,
                                   @Value("${accounting.balance.worker-enabled:false}") boolean enabled,
                                   @Value("${accounting.balance.propagation-period-batch-size:2}") int propagationPeriodBatchSize,
                                   @Value("${accounting.balance.cleanup-batch-size:1000}") int cleanupBatchSize,
                                   @Value("${accounting.balance.cleanup-max-batches:10}") int cleanupMaxBatches,
                                   @Value("${accounting.balance.legacy-tail-rebuild-enabled:false}") boolean legacyTailRebuildEnabled,
                                   MeterRegistry metrics) {
        this.repository = repository;
        this.rebuilds = rebuilds;
        this.enabled = enabled;
        this.metrics = metrics;
        this.propagationPeriodBatchSize = Math.max(1, propagationPeriodBatchSize);
        this.cleanupBatchSize = Math.max(1, Math.min(cleanupBatchSize, 1000));
        this.cleanupMaxBatches = Math.max(1, cleanupMaxBatches);
        this.legacyTailRebuildEnabled = legacyTailRebuildEnabled;
        metrics.gauge("accounting.balance.cleanup.pending", cleanupPending);
        metrics.gauge("accounting.balance.cleanup.oldest-age-seconds", cleanupOldestAgeSeconds);
        metrics.gauge("accounting.balance.remaining-dirty-periods", dirtyPeriods);
        metrics.gauge("accounting.balance.oldest-pending-age-seconds", dirtyOldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${accounting.balance.worker-delay-ms:250}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            Timer.Sample sample = Timer.start(metrics);
            if (rebuilds.processNextJob()) {
                sample.stop(metrics.timer("accounting.balance.rebuild.duration", "outcome", "success"));
                metrics.counter("accounting.balance.rebuilds.processed").increment();
                return;
            }
        } catch (RuntimeException exception) {
            rebuilds.failRunningJob();
            metrics.counter("accounting.balance.rebuilds.failures").increment();
            log.error("Balance projection rebuild failed and was marked for retry", exception);
            return;
        }
        try {
            Timer.Sample sample = Timer.start(metrics);
            var result = repository.applyPendingBatchDetailed(propagationPeriodBatchSize, legacyTailRebuildEnabled);
            if (result.processed()) {
                sample.stop(metrics.timer("accounting.balance.propagation.duration", "outcome", "success"));
                metrics.counter("accounting.balance.events.applied").increment();
                metrics.counter("accounting.balance.processed-periods").increment(result.processedPeriods());
                metrics.counter("accounting.balance.rebuilt-rows").increment(result.rebuiltRows());
            }
        } catch (RuntimeException exception) {
            repository.recordFailure();
            metrics.counter("accounting.balance.projection.failures").increment();
            log.error("Balance projection propagation failed and was scheduled for retry", exception);
        }
        var projectionMetrics = repository.projectionMetrics();
        dirtyPeriods.set(projectionMetrics.remainingDirtyPeriods());
        dirtyOldestAgeSeconds.set(ageSeconds(projectionMetrics.oldestPendingAt()));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        if (enabled) {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(90);
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int removed = repository.cleanupAppliedEvents(cutoff, cleanupBatchSize);
                if (removed > 0) {
                    metrics.counter("accounting.balance.events.cleaned").increment(removed);
                    metrics.counter("accounting.balance.cleanup.batches").increment();
                }
                if (removed < cleanupBatchSize) {
                    break;
                }
            }
            var cleanupMetrics = repository.cleanupMetrics(cutoff);
            cleanupPending.set(cleanupMetrics.pendingEvents());
            cleanupOldestAgeSeconds.set(ageSeconds(cleanupMetrics.oldestCreatedAt()));
        }
    }

    private long ageSeconds(OffsetDateTime timestamp) {
        return timestamp == null ? 0 : java.time.Duration.between(timestamp, OffsetDateTime.now()).toSeconds();
    }
}
