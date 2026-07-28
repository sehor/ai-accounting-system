package com.example.accounting.shared.web;

import com.example.accounting.shared.audit.AuditContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将业务异常统一为 RFC 9457 Problem Details。 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ProblemDetail> handle(ApiProblemException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status()),
                exception.getMessage());
        problem.setTitle(exception.title());
        problem.setProperty("code", exception.code());
        problem.setProperty("traceId", AuditContext.traceId().orElse(null));
        problem.setProperty("retryable", exception.retryable());
        return ResponseEntity.status(exception.status()).body(problem);
    }
}
