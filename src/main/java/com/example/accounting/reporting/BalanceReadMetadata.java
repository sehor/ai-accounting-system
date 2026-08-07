package com.example.accounting.reporting;

import java.time.OffsetDateTime;

/** Request-local metadata used to expose whether a report came from the projection or live facts. */
public final class BalanceReadMetadata {

    private static final ThreadLocal<Metadata> CURRENT = new ThreadLocal<>();

    private BalanceReadMetadata() {
    }

    public static void set(String source, OffsetDateTime asOf, long lagMs) {
        CURRENT.set(new Metadata(source, asOf, Math.max(0, lagMs)));
    }

    public static Metadata current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Metadata(String source, OffsetDateTime asOf, long lagMs) {
    }
}
