package com.example.accounting.agent;

import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import java.util.List;

public final class AgentContextResponses {

    private AgentContextResponses() {
    }

    private static final OperatorContext TOOL_CATALOG = new OperatorContext(List.of(
            new ToolGroup("context", List.of(
                    "get_operator_context", "get_current_user", "list_ledgers", "get_ledger",
                    "get_ledger_role", "get_ledger_context")),
            new ToolGroup("ledger", List.of(
                    "update_ledger", "list_periods", "close_period", "reopen_period",
                    "backup_ledger", "restore_ledger")),
            new ToolGroup("accounts", List.of(
                    "list_accounting_standards", "get_accounting_standard", "list_accounts", "search_accounts",
                    "get_account",
                    "create_account", "ensure_account", "update_account", "delete_account",
                    "update_account_code_rule", "preview_account_import", "get_account_import",
                    "decide_account_import_rows", "commit_account_import", "export_account_template",
                    "export_accounts")),
            new ToolGroup("dimensions_opening", List.of(
                    "list_cash_flow_items", "list_dimension_types", "list_dimension_values",
                    "create_dimension_type", "create_dimension_value", "list_opening_balances",
                    "import_opening_balances", "replace_opening_balances", "confirm_opening_balances")),
            new ToolGroup("documents", List.of(
                    "upload_document", "list_documents", "get_document", "download_document",
                    "extract_document", "list_document_extractions", "get_job_status")),
            new ToolGroup("vouchers", List.of(
                    "list_vouchers", "get_voucher", "create_voucher", "create_voucher_draft",
                    "create_voucher_draft_from_document", "create_voucher_draft_from_document_standard",
                    "validate_voucher", "validate_voucher_standard", "submit_voucher", "approve_voucher",
                    "reject_voucher", "post_voucher", "post_voucher_standard", "update_voucher",
                    "delete_voucher", "list_voucher_revisions",
                    "restore_voucher_revision",
                    "import_kingdee_vouchers", "export_kingdee_vouchers")),
            new ToolGroup("reports", List.of("finance_query", "finance_query_advanced", "export_report")),
            new ToolGroup("assets", List.of("import_fixed_assets")),
            new ToolGroup("experience", List.of(
                    "search_accounting_experiences", "create_accounting_experience",
                    "update_accounting_experience", "archive_accounting_experience"))));

    public static OperatorContext toolCatalog() {
        return TOOL_CATALOG;
    }

    public record OperatorContext(List<ToolGroup> tools) {
    }

    public record ToolGroup(String category, List<String> names) {
    }

    public record LedgerContext(
            LedgerResponses.Ledger ledger,
            LedgerRole role,
            List<LedgerResponses.Period> periods,
            List<LedgerResponses.Account> accounts,
            List<LedgerResponses.DimensionType> dimensionTypes,
            List<LedgerResponses.CashFlowItem> cashFlowItems) {
    }
}
