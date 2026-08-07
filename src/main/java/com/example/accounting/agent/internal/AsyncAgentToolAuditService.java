package com.example.accounting.agent.internal;

import com.example.accounting.agent.internal.port.AgentToolAuditEvent;
import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AsyncAgentToolAuditService {

    private static final Logger log = LoggerFactory.getLogger(AsyncAgentToolAuditService.class);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private final AgentToolAuditRepository repository;
    private final MeterRegistry meters;
    private final BlockingQueue<AgentToolAuditEvent> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final Duration shutdownTimeout;
    private final Clock clock;
    private final Counter dropped;
    private final Counter persistenceFailures;
    private final Timer enqueueTimer;
    private final Timer batchTimer;
    private final Timer persistenceLag;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong nextDropWarning = new AtomicLong();
    private final AtomicLong nextPersistenceWarning = new AtomicLong();
    private volatile Thread worker;

    @Autowired
    public AsyncAgentToolAuditService(AgentToolAuditRepository repository, MeterRegistry meters) {
        this(repository, meters, 10_000, 100, Duration.ofMillis(100), Duration.ofSeconds(5), Clock.systemUTC());
    }

    AsyncAgentToolAuditService(
            AgentToolAuditRepository repository, MeterRegistry meters, int capacity, int batchSize,
            Duration flushInterval, Duration shutdownTimeout, Clock clock) {
        this.repository = repository;
        this.meters = meters;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
        this.shutdownTimeout = shutdownTimeout;
        this.clock = clock;
        this.dropped = meters.counter("accounting.mcp.audit.dropped");
        this.persistenceFailures = meters.counter("accounting.mcp.audit.persistence.failures");
        this.enqueueTimer = Timer.builder("accounting.mcp.audit.enqueue")
                .publishPercentileHistogram().register(meters);
        this.batchTimer = Timer.builder("accounting.mcp.audit.batch.write")
                .publishPercentileHistogram().register(meters);
        this.persistenceLag = Timer.builder("accounting.mcp.audit.persistence.lag")
                .publishPercentileHistogram().register(meters);
        Gauge.builder("accounting.mcp.audit.queue.depth", queue, BlockingQueue::size)
                .register(meters);
    }

    public void recordSuccess(
            String toolName, UUID ledgerId, UUID actorId, String traceId, String inputHash, long durationMs) {
        record(toolName, ledgerId, actorId, traceId, inputHash, "SUCCESS", null, durationMs);
    }

    public void recordFailure(
            String toolName, UUID ledgerId, UUID actorId, String traceId, String inputHash,
            String errorCode, long durationMs) {
        record(toolName, ledgerId, actorId, traceId, inputHash, "FAILURE", errorCode, durationMs);
    }

    private void record(
            String toolName, UUID ledgerId, UUID actorId, String traceId, String inputHash,
            String outcome, String errorCode, long durationMs) {
        try {
            Timer.builder("accounting.mcp.tool.execution")
                    .tag("tool", toolName)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
            AgentToolAuditEvent event = new AgentToolAuditEvent(
                    UUID.randomUUID(), toolName, ledgerId, actorId, traceId, inputHash, null,
                    outcome, errorCode, Math.max(0, durationMs), clock.instant());
            long started = System.nanoTime();
            boolean offered = accepting.get() && queue.offer(event);
            enqueueTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (!offered) {
                dropped.increment();
                warnRateLimited(nextDropWarning,
                        "MCP audit queue is full or stopping; dropped audit events={}", (long) dropped.count());
            }
        } catch (RuntimeException exception) {
            log.warn("MCP audit enqueue failed without affecting the tool result", exception);
        }
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        accepting.set(true);
        worker = Thread.ofPlatform().daemon(true).name("mcp-audit-writer").start(this::run);
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            List<AgentToolAuditEvent> batch = takeBatch();
            if (!batch.isEmpty()) {
                persist(batch);
            }
        }
    }

    private List<AgentToolAuditEvent> takeBatch() {
        List<AgentToolAuditEvent> batch = new ArrayList<>(batchSize);
        try {
            AgentToolAuditEvent first = queue.poll(
                    running.get() ? flushInterval.toMillis() : 0, TimeUnit.MILLISECONDS);
            if (first == null) {
                return batch;
            }
            batch.add(first);
            long deadline = System.nanoTime() + flushInterval.toNanos();
            while (batch.size() < batchSize) {
                if (!running.get()) {
                    queue.drainTo(batch, batchSize - batch.size());
                    break;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                AgentToolAuditEvent next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                batch.add(next);
            }
        } catch (InterruptedException exception) {
            if (running.get()) {
                Thread.currentThread().interrupt();
            }
            queue.drainTo(batch, batchSize - batch.size());
        }
        return batch;
    }

    private void persist(List<AgentToolAuditEvent> batch) {
        long started = System.nanoTime();
        try {
            repository.recordBatch(List.copyOf(batch));
            Instant persistedAt = clock.instant();
            for (AgentToolAuditEvent event : batch) {
                persistenceLag.record(Duration.between(event.occurredAt(), persistedAt));
            }
        } catch (RuntimeException exception) {
            persistenceFailures.increment();
            warnRateLimited(nextPersistenceWarning,
                    "MCP audit batch persistence failed; lost events={}", batch.size(), exception);
        } finally {
            batchTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void warnRateLimited(AtomicLong nextWarning, String message, Object argument) {
        warnRateLimited(nextWarning, message, argument, null);
    }

    private void warnRateLimited(
            AtomicLong nextWarning, String message, Object argument, RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + WARNING_INTERVAL.toNanos())) {
            if (exception == null) {
                log.warn(message, argument);
            } else {
                log.warn(message, argument, exception);
            }
        }
    }

    @PreDestroy
    void stop() {
        accepting.set(false);
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread currentWorker = worker;
        if (currentWorker == null) {
            return;
        }
        currentWorker.interrupt();
        try {
            currentWorker.join(shutdownTimeout.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (currentWorker.isAlive()) {
            log.warn("MCP audit shutdown timed out after {} ms; queued events may be lost",
                    shutdownTimeout.toMillis());
        }
    }
}
