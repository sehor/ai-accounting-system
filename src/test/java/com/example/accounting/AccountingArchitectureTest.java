package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.audit.AuditService;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.JobService;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.voucher.VoucherService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

class AccountingArchitectureTest {

    @Test
    void applicationServicesExposeInterfaces() {
        assertThat(List.of(
                IdentityService.class,
                LedgerService.class,
                VoucherService.class,
                ReportingService.class,
                DocumentService.class,
                ExtractionService.class,
                JobService.class,
                AuditService.class))
                .allSatisfy(type -> assertThat(type).isInterface());
    }

    @Test
    void servicesDoNotDependDirectlyOnJdbc() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        for (var bean : scanner.findCandidateComponents("com.example.accounting")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            assertThat(type.getDeclaredFields())
                    .as("%s must use a repository port instead of JDBC", type.getName())
                    .noneSatisfy(field -> assertThat(JdbcOperations.class)
                            .isAssignableFrom(field.getType()));
        }
    }
}
