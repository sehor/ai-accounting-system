package com.example.accounting.shared.audit;

import java.util.Optional;

/** 当前请求的最小审计上下文。 */
public final class AuditContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private AuditContext() {
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static Optional<String> traceId() {
        return Optional.ofNullable(TRACE_ID.get());
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
