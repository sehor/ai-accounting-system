package com.example.accounting.shared.web;

import com.example.accounting.shared.audit.AuditContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage0HttpSupportTest {

    @AfterEach
    void clearAuditContext() {
        AuditContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void traceIdIsForwardedAndAuditContextIsAvailableDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest,
                                 jakarta.servlet.ServletResponse servletResponse) {
                assertEquals("trace-123", AuditContext.traceId().orElseThrow());
            }
        };

        new TraceIdFilter().doFilter(request, response, chain);

        assertEquals("trace-123", response.getHeader(TraceIdFilter.HEADER_NAME));
        assertTrue(AuditContext.traceId().isEmpty());
    }

    @Test
    void invalidTraceIdIsReplacedWithGeneratedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "bad value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceIdFilter.HEADER_NAME);
        assertNotNull(traceId);
        assertFalse(traceId.contains(" "));
        assertEquals(traceId, request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE));
    }

    @Test
    void businessExceptionMapsToProblemDetailsWithTraceId() {
        AuditContext.setTraceId("trace-123");

        var response = new ProblemDetailExceptionHandler().handle(
                new ApiProblemException(422, "VOUCHER_UNBALANCED", "Voucher validation failed",
                        "Debit and credit totals differ", false));

        assertEquals(422, response.getStatusCode().value());
        assertEquals("VOUCHER_UNBALANCED", response.getBody().getProperties().get("code"));
        assertEquals("trace-123", response.getBody().getProperties().get("traceId"));
        assertEquals(false, response.getBody().getProperties().get("retryable"));
    }

    @Test
    void localUserHeaderAuthenticatesMcpRequestsOnlyWhenEnabled() throws Exception {
        var userId = java.util.UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());

        new LocalUserHeaderAuthenticationFilter(true).doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest servletRequest,
                                         jakarta.servlet.ServletResponse servletResponse) {
                        assertEquals(userId.toString(),
                                SecurityContextHolder.getContext().getAuthentication().getName());
                    }
                });

        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null);
    }

    @Test
    void localAuthenticationDoesNotRepeatARequestWhenDownstreamFails() {
        var calls = new AtomicInteger();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", java.util.UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class, () ->
                new LocalUserHeaderAuthenticationFilter(true).doFilter(
                        request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
                            calls.incrementAndGet();
                            throw new IllegalArgumentException("downstream");
                        }));

        assertEquals(1, calls.get());
    }
}
