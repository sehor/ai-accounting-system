package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.agent.AccountingExperienceService;
import com.example.accounting.audit.AuditService;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.JobService;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.voucher.VoucherService;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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
                AccountingExperienceService.class,
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

    @Test
    void voucherFactsHaveOneWriteBoundaryAndProjectionCannotBeNoop() throws IOException {
        Path root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                String source = Files.readString(file).toLowerCase(java.util.Locale.ROOT);
                boolean writesVoucherFacts = java.util.regex.Pattern.compile(
                        "(insert\\s+into\\s+voucher(_line)?\\s*\\(|"
                                + "update\\s+voucher(_line)?\\s+set|"
                                + "delete\\s+from\\s+voucher(_line)?\\s+where)",
                        java.util.regex.Pattern.DOTALL).matcher(source).find();
                if (writesVoucherFacts) {
                    assertThat(file.getFileName().toString())
                            .as("voucher facts must only be written by the voucher repository")
                            .isEqualTo("JdbcVoucherRepository.java");
                }
                assertThat(source).doesNotContain("noopbalanceprojection");
            }
        }
    }
}
