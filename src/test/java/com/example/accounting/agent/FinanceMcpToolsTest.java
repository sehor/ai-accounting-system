package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
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

        assertThat(names).containsExactlyInAnyOrder(
                "approve_voucher", "archive_accounting_experience", "backup_ledger", "close_period",
                "commit_account_import",
                "confirm_opening_balances", "create_account", "create_dimension_type",
                "create_dimension_value", "create_accounting_experience", "create_voucher", "create_voucher_draft",
                "create_voucher_draft_from_document", "create_voucher_draft_from_document_standard",
                "decide_account_import_rows", "delete_account", "delete_voucher", "download_document",
                "ensure_account", "export_account_template", "export_accounts", "export_kingdee_vouchers",
                "export_report", "extract_document", "finance_query", "finance_query_advanced",
                "get_account", "get_account_import", "get_accounting_standard", "get_current_user",
                "get_document", "get_job_status", "get_ledger", "get_ledger_context", "get_ledger_role",
                "get_operator_context", "get_voucher",
                "import_fixed_assets", "import_kingdee_vouchers", "import_opening_balances", "list_accounting_standards",
                "search_accounting_experiences", "search_accounts",
                "list_accounts", "list_cash_flow_items", "list_dimension_types", "list_dimension_values",
                "list_document_extractions", "list_documents", "list_ledgers", "list_opening_balances",
                "update_ledger",
                "list_periods", "list_voucher_revisions", "list_vouchers", "post_voucher",
                "post_voucher_standard", "preview_account_import", "reject_voucher", "reopen_period",
                "replace_opening_balances", "restore_deleted_voucher", "restore_ledger",
                "restore_voucher_revision", "submit_voucher",
                "update_account", "update_account_code_rule", "update_accounting_experience", "update_voucher",
                "upload_document",
                "validate_voucher", "validate_voucher_standard");
        assertThat(names).doesNotContain("decide_account_import_row");
        assertThat(names).noneMatch(name -> name.matches(".*(create_ledger|audit|member).*")
                || name.equals("list_members") || name.equals("update_member") || name.equals("remove_member"));
    }

    @Test
    void operatorContextContainsOnlyTheCategorizedToolCatalog() {
        assertThat(Arrays.stream(AgentContextResponses.OperatorContext.class.getRecordComponents())
                .map(component -> component.getName()).toList()).containsExactly("tools");

        List<String> catalogNames = AgentContextResponses.toolCatalog().tools().stream()
                .flatMap(group -> group.names().stream())
                .toList();
        Set<String> exposedNames = Arrays.stream(FinanceMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertThat(catalogNames).doesNotHaveDuplicates();
        assertThat(catalogNames).containsExactlyInAnyOrderElementsOf(exposedNames);
    }

    @Test
    void toolDescriptionsStayShortAndRouteFocused() {
        List<String> descriptions = Arrays.stream(FinanceMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::description)
                .toList();

        assertThat(descriptions).allMatch(description -> !description.isBlank() && description.length() <= 48);
        assertThat(descriptions).noneMatch(description -> description.matches(
                "(?i).*(authenticated|available to|visible in|base64 encoded|one installed|every ledger).*"));
    }
}
