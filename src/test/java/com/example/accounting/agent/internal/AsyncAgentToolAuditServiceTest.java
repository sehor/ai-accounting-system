package com.example.accounting.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.agent.internal.port.AgentToolAuditEvent;
import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AsyncAgentToolAuditServiceTest {

    private final List<AsyncAgentToolAuditService> services = new ArrayList<>();

    @AfterEach
    void stopServices() {
        services.forEach(AsyncAgentToolAuditService::stop);
    }

    @Test
    void persistsAFullBatchWithoutWaitingForTheFlushInterval() {
        RecordingRepository repository = new RecordingRepository();
        AsyncAgentToolAuditService service = service(repository, 10, 2, Duration.ofSeconds(5));

        service.recordSuccess("get_ledger", UUID.randomUUID(), UUID.randomUUID(), "trace-1", "input-1", 12);
        service.recordSuccess("list_accounts", UUID.randomUUID(), UUID.randomUUID(), "trace-2", "input-2", 18);

        await(() -> repository.events.size() == 2);
        assertThat(repository.batchSizes).containsExactly(2);
        assertThat(repository.events).allMatch(event -> event.resultHash() == null);
    }

    @Test
    void dropsImmediatelyAndRateLimitsWarningsWhenTheBoundedQueueIsFull() throws Exception {
        BlockingRepository repository = new BlockingRepository();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AsyncAgentToolAuditService service = service(repository, meters, 1, 1, Duration.ofMillis(10));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AsyncAgentToolAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.recordSuccess("first", null, UUID.randomUUID(), "trace-1", "input-1", 1);
            assertThat(repository.entered.await(1, TimeUnit.SECONDS)).isTrue();
            service.recordSuccess("queued", null, UUID.randomUUID(), "trace-2", "input-2", 1);
            service.recordSuccess("dropped", null, UUID.randomUUID(), "trace-3", "input-3", 1);
            service.recordSuccess("also-dropped", null, UUID.randomUUID(), "trace-4", "input-4", 1);

            assertThat(meters.counter("accounting.mcp.audit.dropped").count()).isEqualTo(2);
            assertThat(appender.list).filteredOn(event -> event.getFormattedMessage()
                    .startsWith("MCP audit queue is full or stopping")).hasSize(1);
        } finally {
            logger.detachAppender(appender);
            repository.release.countDown();
        }
    }

    @Test
    void persistenceFailureNeverEscapesToTheCallingThread() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AsyncAgentToolAuditService service = service(events -> {
            throw new IllegalStateException("audit store unavailable");
        }, meters, 10, 1, Duration.ofMillis(10));

        service.recordFailure("finance_query", null, UUID.randomUUID(), "trace", "input",
                "FINANCE_QUERY_INVALID", 7);

        await(() -> meters.counter("accounting.mcp.audit.persistence.failures").count() == 1);
    }

    @Test
    void stopDrainsQueuedEventsBeforeReturning() {
        RecordingRepository repository = new RecordingRepository();
        AsyncAgentToolAuditService service = service(repository, 10, 100, Duration.ofSeconds(5));
        service.recordSuccess("get_current_user", null, UUID.randomUUID(), "trace", "input", 2);

        service.stop();

        assertThat(repository.events).hasSize(1);
    }

    private AsyncAgentToolAuditService service(
            AgentToolAuditRepository repository, int capacity, int batchSize, Duration flushInterval) {
        return service(repository, new SimpleMeterRegistry(), capacity, batchSize, flushInterval);
    }

    private AsyncAgentToolAuditService service(
            AgentToolAuditRepository repository, SimpleMeterRegistry meters,
            int capacity, int batchSize, Duration flushInterval) {
        AsyncAgentToolAuditService service = new AsyncAgentToolAuditService(
                repository, meters, capacity, batchSize, flushInterval, Duration.ofSeconds(1), Clock.systemUTC());
        services.add(service);
        service.start();
        return service;
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class RecordingRepository implements AgentToolAuditRepository {
        private final List<AgentToolAuditEvent> events = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public synchronized void recordBatch(List<AgentToolAuditEvent> batch) {
            batchSizes.add(batch.size());
            events.addAll(batch);
        }
    }

    private static final class BlockingRepository implements AgentToolAuditRepository {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void recordBatch(List<AgentToolAuditEvent> batch) {
            entered.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
