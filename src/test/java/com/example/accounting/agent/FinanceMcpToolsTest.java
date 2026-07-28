package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class FinanceMcpToolsTest {

    @Test
    void exposesOnlyTheWhitelistedTools() {
        Set<String> names = Arrays.stream(FinanceMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("list_ledgers", "finance_query", "get_voucher",
                "create_voucher_draft", "validate_voucher", "upload_document", "extract_document",
                "get_job_status", "create_voucher_draft_from_document");
        assertThat(names).noneMatch(name -> name.matches(".*(post|approve|close|reopen|member).*"));
    }
}
