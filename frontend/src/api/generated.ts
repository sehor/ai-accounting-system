export interface paths {
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get"];
        put: operations["update"];
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/draft": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["updateDraft"];
        post: operations["createDraft"];
        delete: operations["deleteDraft"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/opening-balances": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listOpeningBalances"];
        put: operations["replaceOpeningBalances"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-imports/{importId}/rows/{rowNo}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["decide"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-code-rule": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getAccountCodeRule"];
        put: operations["updateAccountCodeRule"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_1"];
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}:validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["validate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}:submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}:reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reject"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}:post": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["post"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}:approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["approve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}/revisions/{revision}:restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restoreRevision"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}:publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["publish"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/versions/{version}:rollback": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["rollback"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/draft:reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resetDraft"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/draft:preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["preview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/periods/{periodId}:reopen": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reopenPeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/periods/{periodId}:close": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["closePeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/period-closings/{periodId}/steps/{step}:reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/period-closings/{periodId}/steps/{step}:generate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["generate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/opening-balances:import-csv": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["importOpeningBalances"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/opening-balances:confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["confirmOpeningBalances"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listMembers"];
        put?: never;
        post: operations["addMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["assets"];
        put?: never;
        post: operations["createAsset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/{assetId}:dispose": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["dispose"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/{assetId}:copy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["copyAsset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/{assetId}:cancel-disposal": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancelDisposal"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["importAssets"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-depreciation:regenerate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["regenerate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-depreciation:generate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["generate_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["categories"];
        put?: never;
        post: operations["createCategory"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/finance-query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["query"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_2"];
        put?: never;
        post: operations["upload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents/{documentId}:extract": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["extract"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents/{documentId}:create-voucher-draft": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["createVoucherDraft"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/dimension-values:batch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["listDimensionValues"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/dimension-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listDimensionTypes"];
        put?: never;
        post: operations["createDimensionType"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listDimensionValues_1"];
        put?: never;
        post: operations["createDimensionValue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/data-exchange/kingdee:import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["importKingdee"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/books/dimension-ledger:query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["dimensionLedger"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/balance-rebuilds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["request"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/accounts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listAccounts"];
        put?: never;
        post: operations["createAccount"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-imports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["preview_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-imports/{importId}:commit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["commit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledger-restores": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restore"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}:restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restoreUser"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/ledgers/{ledgerId}:restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restoreLedger"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["update_1"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/period-closing-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["settings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateSettings"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["removeMember"];
        options?: never;
        head?: never;
        patch: operations["updateMember"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/{assetId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["asset"];
        put?: never;
        post?: never;
        delete: operations["deleteAsset"];
        options?: never;
        head?: never;
        patch: operations["updateAsset"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-categories/{categoryId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["category"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateCategory"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/dimension-types/{typeId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateDimensionType"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values/{valueId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateDimensionValue"];
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/accounts/{accountId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getAccount"];
        put?: never;
        post?: never;
        delete: operations["deleteAccount"];
        options?: never;
        head?: never;
        patch: operations["updateAccount"];
        trace?: never;
    };
    "/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/vouchers/{voucherId}/revisions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["revisions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/role": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["role"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/trial-balance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["trialBalance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/sub-ledger": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["subLedger"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/statutory/{reportType}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["statutory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/income-statement": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["incomeStatement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/general-ledger": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["generalLedger"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/reports/balance-sheet": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["balanceSheet"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["workspace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["versions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/report-formulas/{code}/versions/{version}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["version"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/periods": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listPeriods"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/period-closings/{periodId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["status"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/member-candidates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["findMemberCandidates"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/jobs/{jobId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-assets/import-template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["importTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-depreciation/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["runs"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/fixed-asset-depreciation/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["preview_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents/{documentId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents/{documentId}/extractions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["extractions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/documents/{documentId}/content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["content"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/data-exchange/kingdee:export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["exportKingdee"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/cash-flow-items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listCashFlowItems"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/books/sub-ledger": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["subLedger_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/books/general-ledger": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["generalLedger_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/balance-rebuilds/{jobId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["find"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/backup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["backup"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/audit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/accounts/{accountId}/next-child-code": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["nextChildAccountCode"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/accounts/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["searchAccounts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-imports/{importId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_4"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-import-template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["template"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/ledgers/{ledgerId}/account-export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["export"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listUsers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/ledgers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listLedgers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/accounting-standards": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_4"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/accounting-standards/{code}/versions/{version}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_5"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["deleteUser"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/ledgers/{ledgerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["deleteLedger"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @default null */
        VoucherDimensionRequest: {
            /** Format: uuid */
            dimensionTypeId: string;
            /** Format: uuid */
            dimensionValueId: string;
        };
        /** @default null */
        VoucherLineRequest: {
            /** Format: uuid */
            accountId?: string;
            side: string;
            currency: string;
            originalAmount?: string;
            exchangeRate: string;
            summary?: string;
            /** Format: uuid */
            cashFlowItemId?: string;
            quantity?: string;
            unitPrice?: string;
            dimensions?: components["schemas"]["VoucherDimensionRequest"][];
        };
        /** @default null */
        VoucherUpdateRequest: {
            /** Format: int64 */
            expectedVersion: number;
            /** Format: uuid */
            periodId: string;
            /** Format: date */
            voucherDate: string;
            voucherType: string;
            voucherNumber: string;
            summary?: string;
            lines: components["schemas"]["VoucherLineRequest"][];
        };
        /** @default null */
        Voucher: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            /** Format: uuid */
            periodId: string;
            /** Format: date */
            voucherDate: string;
            voucherType: string;
            /** @default  */
            voucherNumber: string | null;
            /** @default  */
            summary: string | null;
            status: string;
            approvalRequired: boolean;
            /** Format: int64 */
            version: number;
            lines: components["schemas"]["VoucherLineResponse"][];
            /** @default  */
            sourceType: string | null;
            /**
             * Format: uuid
             * @default
             */
            sourceId: string | null;
        };
        /** @default null */
        VoucherDimensionResponse: {
            /** Format: uuid */
            dimensionTypeId: string;
            /** Format: uuid */
            dimensionValueId: string;
        };
        /** @default null */
        VoucherLineResponse: {
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            lineNo: number;
            /** Format: uuid */
            accountId: string;
            side: string;
            currency: string;
            originalAmount: string;
            exchangeRate: string;
            baseAmount: string;
            /** @default  */
            summary: string | null;
            /**
             * Format: uuid
             * @default
             */
            cashFlowItemId: string | null;
            /** @default  */
            quantity: string | null;
            /** @default  */
            unitPrice: string | null;
            dimensions: components["schemas"]["VoucherDimensionResponse"][];
        };
        AccountReference: {
            type?: string;
            value?: string;
        };
        /** @default null */
        ReportFormulaDraftUpdate: {
            /** Format: int64 */
            expectedDraftVersion: number;
            lines?: components["schemas"]["ReportFormulaLineEdit"][];
            rules?: components["schemas"]["ReportFormulaRuleEdit"][];
        };
        /** @default null */
        ReportFormulaLineEdit: {
            lineKey: string;
            name: string;
            expression: unknown;
        };
        /** @default null */
        ReportFormulaRuleEdit: {
            key: string;
            side: string;
            categories?: string[];
            accounts?: components["schemas"]["AccountReference"][];
        };
        /** @default null */
        ReportFormulaDraft: {
            /** Format: int64 */
            version: number;
            /** Format: int32 */
            basePublishedVersion: number;
            definition: unknown;
            /** Format: int64 */
            lastPreviewedDraftVersion?: number;
            previewHasWarnings?: boolean;
            /** Format: date-time */
            updatedAt?: string;
        };
        /** @default null */
        OpeningBalanceDimensionRequest: {
            /** Format: uuid */
            dimensionTypeId: string;
            /** Format: uuid */
            dimensionValueId: string;
        };
        OpeningBalanceLine: {
            /** Format: uuid */
            accountId: string;
            /** Format: uuid */
            periodId: string;
            currency: string;
            dimensionKey?: string;
            debitOriginal: string;
            creditOriginal: string;
            exchangeRate: string;
            dimensions?: components["schemas"]["OpeningBalanceDimensionRequest"][];
        };
        OpeningBalances: {
            lines: components["schemas"]["OpeningBalanceLine"][];
            reason?: string;
        };
        /** @default null */
        OpeningBalanceDimensionResponse: {
            /** Format: uuid */
            dimensionTypeId: string;
            /** Format: uuid */
            dimensionValueId: string;
            dimensionTypeCode: string;
            dimensionTypeName: string;
            dimensionValueCode: string;
            dimensionValueName: string;
        };
        /** @default null */
        OpeningBalanceResponse: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            /** Format: uuid */
            periodId: string;
            /** Format: uuid */
            accountId: string;
            currency: string;
            /** @default  */
            dimensionKey: string | null;
            debitOriginal: string;
            creditOriginal: string;
            exchangeRate: string;
            debitBase: string;
            creditBase: string;
            confirmed: boolean;
            dimensions: components["schemas"]["OpeningBalanceDimensionResponse"][];
        };
        /** @default null */
        AccountImportDecision: {
            /** @default  */
            action: string | null;
            /**
             * Format: uuid
             * @default
             */
            targetAccountId: string | null;
            /** @default  */
            accountCode: string | null;
        };
        /** @default null */
        AccountImportPreview: {
            /**
             * Format: uuid
             * @default
             */
            id: string;
            /**
             * Format: uuid
             * @default
             */
            ledgerId: string;
            /**
             * @default
             * @enum {string}
             */
            format: "STANDARD" | "KINGDEE";
            /** @default  */
            status: string;
            /**
             * Format: int64
             * @default
             */
            ledgerVersion: number;
            /** @default  */
            filename: string;
            /**
             * Format: int32
             * @default
             */
            rowCount: number;
            /**
             * Format: int32
             * @default
             */
            errorCount: number;
            /** @default  */
            aiStatus: string;
            /** @default  */
            rows: components["schemas"]["AccountImportPreviewRow"][];
        };
        /** @default null */
        AccountImportPreviewRow: {
            /**
             * Format: int32
             * @default
             */
            rowNo: number;
            /** @default  */
            rawData: {
                [key: string]: string;
            };
            /** @default  */
            cleanedData: {
                [key: string]: string;
            };
            /** @default  */
            accountCode: string;
            /**
             * Format: uuid
             * @default
             */
            targetAccountId: string | null;
            /**
             * Format: int64
             * @default
             */
            expectedAccountVersion: number | null;
            /** @default  */
            action: string | null;
            /** @default false */
            confirmed: boolean;
            /** @default  */
            confidence: string | null;
            /** @default  */
            issues: string[];
        };
        AccountCodeRuleUpdate: {
            /** Format: int32 */
            level2Width: number;
            /** Format: int32 */
            level3Width: number;
            /** Format: int32 */
            level4Width: number;
        };
        /** @default null */
        AccountCodeRule: {
            /** Format: int32 */
            level2Width: number;
            /** Format: int32 */
            level3Width: number;
            /** Format: int32 */
            level4Width: number;
        };
        /** @default null */
        LedgerCreateRequest: {
            name: string;
            description?: string;
            accountingStandardCode: string;
            accountingStandardVersion: string;
            baseCurrency: string;
            /** Format: date */
            startDate: string;
            approvalEnabled?: boolean;
            accountCodeRule?: components["schemas"]["AccountCodeRule"];
        };
        /** @default null */
        LedgerResponse: {
            /** Format: uuid */
            id: string;
            name: string;
            description: string;
            accountingStandardCode: string;
            accountingStandardVersion: string;
            baseCurrency: string;
            /** Format: date */
            startDate: string;
            approvalEnabled: boolean;
            status: string;
        };
        /** @default null */
        VoucherCreateRequest: {
            /** Format: uuid */
            periodId?: string;
            /** Format: date */
            voucherDate: string;
            voucherType: string;
            voucherNumber?: string;
            summary?: string;
            lines: components["schemas"]["VoucherLineRequest"][];
        };
        Comment: {
            comment: string;
        };
        /** @default null */
        ReportFormulaPublishRequest: {
            /** Format: int32 */
            expectedPublishedVersion: number;
            /** Format: int64 */
            expectedDraftVersion: number;
            acknowledgeWarnings?: boolean;
        };
        /** @default null */
        ReportFormulaPublishResult: {
            formulaCode: string;
            /** Format: int32 */
            publishedVersion: number;
        };
        /** @default null */
        ReportFormulaRollbackRequest: {
            /** Format: int32 */
            expectedPublishedVersion: number;
        };
        /** @default null */
        ReportFormulaRollbackResult: {
            formulaCode: string;
            /** Format: int32 */
            publishedVersion: number;
        };
        /** @default null */
        ReportFormulaDraftReset: {
            /** Format: int64 */
            expectedDraftVersion: number;
        };
        /** @default null */
        ReportFormulaPreviewRequest: {
            /** Format: int64 */
            expectedDraftVersion: number;
            /** @default  */
            periodCode: string | null;
            /** @default  */
            periodFrom: string | null;
            /** @default  */
            periodTo: string | null;
        };
        /** @default null */
        ReportFormulaIssue: {
            code: string;
            path: string;
            message: string;
        };
        /** @default null */
        ReportFormulaPreviewResult: {
            /** Format: int64 */
            draftVersion: number;
            /** Format: int64 */
            previewedDraftVersion: number;
            previewHasWarnings: boolean;
            blockingIssues?: components["schemas"]["ReportFormulaIssue"][];
            warnings?: components["schemas"]["ReportFormulaWarning"][];
            statement?: unknown;
        };
        /** @default null */
        ReportFormulaWarning: {
            code: string;
            name: string;
            difference: string;
        };
        PeriodAction: {
            reason: string;
        };
        /** @default null */
        Period: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            periodCode: string;
            /** Format: date */
            startDate: string;
            /** Format: date */
            endDate: string;
            status: string;
            hasVouchers: boolean;
        };
        Reset: {
            reason: string;
        };
        /** @default null */
        PeriodClosingBlocker: {
            /** @default  */
            code: string;
            /** @default  */
            title: string;
            /** @default  */
            detail: string;
        };
        /** @default null */
        PeriodClosingStep: {
            /**
             * @default
             * @enum {string}
             */
            step: "DEPRECIATION" | "EXPENSE_TRANSFER" | "REVENUE_TRANSFER" | "YEAR_END_PROFIT_TRANSFER";
            /**
             * @default
             * @enum {string}
             */
            status: "NOT_REQUIRED" | "PENDING" | "GENERATED" | "STALE" | "BLOCKED";
            /** @default  */
            amount: string;
            /**
             * Format: uuid
             * @default
             */
            voucherId: string | null;
            /** @default  */
            inputFingerprint: string | null;
            /** @default  */
            blockers: components["schemas"]["PeriodClosingBlocker"][];
            /**
             * Format: date-time
             * @default
             */
            updatedAt: string;
        };
        AddMember: {
            /** Format: uuid */
            userId: string;
            /** @enum {string} */
            role: "OWNER" | "EDITOR" | "REVIEWER" | "VIEWER" | "AGENT";
        };
        /** @default null */
        Member: {
            /** Format: uuid */
            userId: string;
            /** @enum {string} */
            role: "OWNER" | "EDITOR" | "REVIEWER" | "VIEWER" | "AGENT";
            /** @enum {string} */
            status: "ACTIVE" | "INACTIVE";
            displayName: string;
            /** @default  */
            email: string | null;
        };
        AssetCreate: {
            /** Format: uuid */
            categoryId: string;
            code: string;
            name: string;
            quantity: string;
            /** Format: date */
            serviceDate: string;
            originalCost: string;
            inputTax: string;
            /** Format: int32 */
            usefulLifeMonths: number;
            residualRate: string;
            openingAccumulatedDepreciation: string;
            /** Format: int32 */
            openingDepreciatedMonths: number;
            impairmentAmount: string;
            /** Format: uuid */
            departmentValueId?: string;
            /** Format: uuid */
            acquisitionVoucherId?: string;
            /** Format: uuid */
            assetAccountId?: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId?: string;
            /** Format: uuid */
            depreciationExpenseAccountId?: string;
            /** Format: uuid */
            impairmentAccountId?: string;
            /** Format: uuid */
            clearingAccountId?: string;
            /** Format: uuid */
            disposalGainAccountId?: string;
            /** Format: uuid */
            disposalLossAccountId?: string;
            note?: string;
        };
        /** @default null */
        FixedAsset: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            /** Format: uuid */
            categoryId: string;
            categoryCode: string;
            categoryName: string;
            code: string;
            name: string;
            status: string;
            quantity: string;
            /** Format: date */
            serviceDate: string;
            originalCost: string;
            inputTax: string;
            /** Format: int32 */
            usefulLifeMonths: number;
            residualRate: string;
            residualAmount: string;
            openingAccumulatedDepreciation: string;
            /** Format: int32 */
            openingDepreciatedMonths: number;
            impairmentAmount: string;
            currentDepreciation: string;
            currentAccumulatedDepreciation: string;
            endingAccumulatedDepreciation: string;
            openingNetValue: string;
            endingNetValue: string;
            /**
             * Format: uuid
             * @default
             */
            departmentValueId: string | null;
            /**
             * Format: uuid
             * @default
             */
            acquisitionVoucherId: string | null;
            /** Format: uuid */
            assetAccountId: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId: string;
            /** Format: uuid */
            depreciationExpenseAccountId: string;
            /**
             * Format: uuid
             * @default
             */
            impairmentAccountId: string | null;
            /** Format: uuid */
            clearingAccountId: string;
            /** Format: uuid */
            disposalGainAccountId: string;
            /** Format: uuid */
            disposalLossAccountId: string;
            /**
             * Format: date
             * @default
             */
            disposalDate: string | null;
            /** @default  */
            note: string | null;
            /** Format: int64 */
            version: number;
        };
        /** @default null */
        FixedAssetDisposalRequest: {
            /** Format: uuid */
            periodId: string;
            /** Format: date */
            disposalDate: string;
            reason: string;
            proceeds: string;
            outputTax: string;
            clearingCost: string;
            clearingInputTax: string;
            /** Format: uuid */
            receiptAccountId?: string;
            /** Format: uuid */
            paymentAccountId?: string;
            /** Format: uuid */
            outputTaxAccountId?: string;
            /** Format: uuid */
            inputTaxAccountId?: string;
        };
        /** @default null */
        FixedAssetDisposalResponse: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            assetId: string;
            /** Format: uuid */
            periodId: string;
            /**
             * Format: uuid
             * @default
             */
            depreciationVoucherId: string | null;
            /**
             * Format: uuid
             * @default
             */
            transferVoucherId: string | null;
            /**
             * Format: uuid
             * @default
             */
            settlementVoucherId: string | null;
            carryingAmount: string;
            gainOrLoss: string;
        };
        DisposalCancellation: {
            reason: string;
            /** Format: int64 */
            expectedVersion: number;
        };
        /** @default null */
        FixedAssetImportResult: {
            /** Format: int32 */
            rowCount: number;
            /** Format: int32 */
            errorCount: number;
            committed: boolean;
            errors: string[];
        };
        DepreciationAction: {
            /** Format: uuid */
            periodId: string;
            reason?: string;
        };
        /** @default null */
        FixedAssetDepreciationRun: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            periodId: string;
            runType: string;
            status: string;
            /**
             * Format: uuid
             * @default
             */
            voucherId: string | null;
            totalAmount: string;
            inputFingerprint: string;
            /** Format: date-time */
            createdAt: string;
        };
        CategoryCreate: {
            code: string;
            name: string;
            /** Format: int32 */
            usefulLifeMonths: number;
            residualRate: string;
            /** Format: uuid */
            assetAccountId: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId: string;
            /** Format: uuid */
            depreciationExpenseAccountId: string;
            /** Format: uuid */
            impairmentAccountId?: string;
            /** Format: uuid */
            clearingAccountId: string;
            /** Format: uuid */
            disposalGainAccountId: string;
            /** Format: uuid */
            disposalLossAccountId: string;
        };
        /** @default null */
        FixedAssetCategory: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            code: string;
            name: string;
            /** Format: int32 */
            usefulLifeMonths: number;
            residualRate: string;
            /** Format: uuid */
            assetAccountId: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId: string;
            /** Format: uuid */
            depreciationExpenseAccountId: string;
            /**
             * Format: uuid
             * @default
             */
            impairmentAccountId: string | null;
            /** Format: uuid */
            clearingAccountId: string;
            /** Format: uuid */
            disposalGainAccountId: string;
            /** Format: uuid */
            disposalLossAccountId: string;
            status: string;
            /** Format: int64 */
            version: number;
        };
        Filters: {
            accountCodes?: string[];
            currency?: string;
            dimensionValues?: components["schemas"]["FinanceQueryDimensionValue"][];
        };
        /** @default null */
        FinanceQueryDimensionValue: {
            /** Format: uuid */
            dimensionTypeId?: string;
            /** Format: uuid */
            dimensionValueId?: string;
        };
        /** @default null */
        FinanceQueryRequest: {
            metric: string;
            periodFrom?: string;
            periodTo?: string;
            groupBy: string[];
            filters?: components["schemas"]["Filters"];
            dimensionGroupTypeIds?: string[];
        };
        /** @default null */
        FinanceQueryDimension: {
            /** Format: uuid */
            dimensionTypeId: string;
            /** Format: uuid */
            dimensionValueId: string;
            dimensionTypeCode: string;
            dimensionTypeName: string;
            dimensionValueCode: string;
            dimensionValueName: string;
        };
        /** @default null */
        FinanceQueryLine: {
            /** @default  */
            groupKey: string | null;
            amount: string;
            /** @default  */
            dimensionKey: string | null;
            dimensions: components["schemas"]["FinanceQueryDimension"][];
            /** @default  */
            currency: string | null;
            /** @default  */
            periodCode: string | null;
            /** @default  */
            accountCode: string | null;
        };
        /** @default null */
        DocumentResponse: {
            /**
             * Format: uuid
             * @default
             */
            id: string;
            /**
             * Format: uuid
             * @default
             */
            ledgerId: string;
            /** @default  */
            objectKey: string;
            /** @default  */
            fileName: string;
            /** @default  */
            contentType: string;
            /**
             * Format: int64
             * @default
             */
            sizeBytes: number;
            /** @default  */
            sha256: string;
            /** @default  */
            status: string;
            /** @default false */
            duplicateWarning: boolean;
            /**
             * Format: date-time
             * @default
             */
            createdAt: string;
        };
        /** @default null */
        DocumentExtraction: {
            /**
             * Format: uuid
             * @default
             */
            id: string;
            /**
             * Format: uuid
             * @default
             */
            documentId: string;
            /** @default  */
            provider: string;
            /** @default  */
            status: string;
            /** @default  */
            structuredResult: string;
        };
        /** @default null */
        DimensionValuesBatchRequest: {
            dimensionTypeIds: string[];
        };
        /** @default null */
        DimensionValueGroup: {
            /** Format: uuid */
            dimensionTypeId: string;
            values: components["schemas"]["LedgerDimensionValue"][];
        };
        /** @default null */
        DimensionValuesBatchResponse: {
            groups: components["schemas"]["DimensionValueGroup"][];
        };
        /** @default null */
        LedgerDimensionValue: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            /** Format: uuid */
            dimensionTypeId: string;
            code: string;
            name: string;
            status: string;
            /** Format: int64 */
            version: number;
        };
        DimensionTypeCreate: {
            code: string;
            name: string;
            required?: boolean;
        };
        /** @default null */
        DimensionType: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            code: string;
            name: string;
            required: boolean;
            status: string;
            /** Format: int64 */
            version: number;
        };
        DimensionValueCreate: {
            code: string;
            name: string;
        };
        /** @default null */
        KingdeeImportResult: {
            /** Format: int32 */
            voucherCount: number;
            /** Format: int32 */
            rowCount: number;
        };
        /** @default null */
        DimensionLedgerDimensionValue: {
            /** Format: uuid */
            dimensionTypeId?: string;
            /** Format: uuid */
            dimensionValueId?: string;
        };
        /** @default null */
        DimensionLedgerQuery: {
            periodFrom?: string;
            periodTo?: string;
            /** Format: uuid */
            accountId: string;
            currency?: string;
            dimensionValues?: components["schemas"]["DimensionLedgerDimensionValue"][];
            groupDimensionTypeIds?: string[];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            pageSize?: number;
        };
        /** @default null */
        DimensionLedgerAmounts: {
            openingDebit: string;
            openingCredit: string;
            periodDebit: string;
            periodCredit: string;
            closingDebit: string;
            closingCredit: string;
        };
        /** @default null */
        DimensionLedgerBalance: {
            /** Format: uuid */
            combinationId: string;
            dimensionKey: string;
            combinationKind: string;
            /** @default  */
            groupKey: string | null;
            currency: string;
            dimensions: components["schemas"]["FinanceQueryDimension"][];
            original: components["schemas"]["DimensionLedgerAmounts"];
            base: components["schemas"]["DimensionLedgerAmounts"];
        };
        /** @default null */
        DimensionLedgerEntry: {
            /** Format: uuid */
            voucherId: string;
            voucherNumber: string;
            /** Format: date */
            voucherDate: string;
            /** Format: int32 */
            lineNo: number;
            /** Format: uuid */
            lineId: string;
            /** Format: uuid */
            accountId: string;
            accountCode: string;
            accountName: string;
            /** Format: uuid */
            combinationId: string;
            dimensionKey: string;
            combinationKind: string;
            /** @default  */
            groupKey: string | null;
            dimensions: components["schemas"]["FinanceQueryDimension"][];
            currency: string;
            side: string;
            originalDebit: string;
            originalCredit: string;
            baseDebit: string;
            baseCredit: string;
            runningOriginalDebit: string;
            runningOriginalCredit: string;
            runningBaseDebit: string;
            runningBaseCredit: string;
        };
        /** @default null */
        DimensionLedgerPage: {
            projectionStatus: string;
            warnings: string[];
            balances: components["schemas"]["DimensionLedgerBalance"][];
            entries: components["schemas"]["DimensionLedgerEntry"][];
            pagination: components["schemas"]["Pagination"];
        };
        /** @default null */
        Pagination: {
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            pageSize: number;
            /** Format: int64 */
            totalItems: number;
            /** Format: int32 */
            totalPages: number;
        };
        /** @default null */
        BalanceRebuildCreateRequest: {
            periodFrom?: string;
            periodTo?: string;
            reason: string;
        };
        /** @default null */
        BalanceRebuildJob: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            periodFrom: string;
            periodTo: string;
            status: string;
            reason: string;
            /** Format: uuid */
            requestedBy: string;
            /** Format: int32 */
            processedPeriods: number;
            /** Format: int32 */
            totalPeriods: number;
            /** Format: int32 */
            differenceCount: number;
            /** Format: date-time */
            createdAt: string;
            /**
             * Format: date-time
             * @default
             */
            startedAt: string | null;
            /**
             * Format: date-time
             * @default
             */
            completedAt: string | null;
            /** @default  */
            errorCode: string | null;
            /** @default  */
            errorMessage: string | null;
        };
        AccountCreate: {
            code: string;
            name: string;
            standardAccountKey?: string;
            category: string;
            normalBalance: string;
            /** Format: uuid */
            parentId?: string;
            cashFlowRequired?: boolean;
            /** Format: uuid */
            defaultCashFlowItemId?: string;
            quantityEnabled?: boolean;
            unitName?: string;
            dimensionRequirements?: components["schemas"]["AccountDimensionRequirementRequest"][];
        };
        /** @default null */
        AccountDimensionRequirementRequest: {
            /** Format: uuid */
            dimensionTypeId: string;
            required?: boolean;
        };
        /** @default null */
        Account: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            code: string;
            name: string;
            /** @default  */
            standardAccountKey: string | null;
            category: string;
            normalBalance: string;
            status: string;
            /**
             * Format: uuid
             * @default
             */
            parentId: string | null;
            /** Format: int32 */
            level: number;
            isLeaf: boolean;
            isTemplate: boolean;
            hasBusinessUsage: boolean;
            coreLocked: boolean;
            legacyCode: boolean;
            /** Format: int64 */
            version: number;
            cashFlowRequired: boolean;
            /**
             * Format: uuid
             * @default
             */
            defaultCashFlowItemId: string | null;
            quantityEnabled: boolean;
            /** @default  */
            unitName: string | null;
            dimensionRequirements: components["schemas"]["AccountDimensionRequirementResponse"][];
            /**
             * Format: date-time
             * @default
             */
            createdAt: string | null;
        };
        /** @default null */
        AccountDimensionRequirementResponse: {
            /** Format: uuid */
            dimensionTypeId: string;
            code: string;
            name: string;
            required: boolean;
        };
        /** @default null */
        AdminUser: {
            /** Format: uuid */
            id: string;
            issuer: string;
            subject: string;
            displayName: string;
            /** @default  */
            email: string | null;
            /** @enum {string} */
            userType: "HUMAN" | "AGENT";
            status: string;
            deleted: boolean;
            protectedUser: boolean;
        };
        /** @default null */
        AdminLedger: {
            /** Format: uuid */
            id: string;
            name: string;
            description: string;
            accountingStandardCode: string;
            accountingStandardVersion: string;
            baseCurrency: string;
            /** Format: date */
            startDate: string;
            approvalEnabled: boolean;
            status: string;
            deleted: boolean;
        };
        Rename: {
            name: string;
            description?: string;
        };
        SettingsPatch: {
            /** Format: uuid */
            profitAccountId?: string;
            /** Format: uuid */
            retainedEarningsAccountId?: string;
        };
        /** @default null */
        PeriodClosingSettings: {
            /**
             * Format: uuid
             * @default
             */
            ledgerId: string;
            /**
             * Format: uuid
             * @default
             */
            profitAccountId: string | null;
            /**
             * Format: uuid
             * @default
             */
            retainedEarningsAccountId: string | null;
            /**
             * Format: uuid
             * @default
             */
            defaultProfitAccountId: string | null;
            /**
             * Format: uuid
             * @default
             */
            defaultRetainedEarningsAccountId: string | null;
            /**
             * Format: int64
             * @default
             */
            version: number;
        };
        UpdateMember: {
            /** @enum {string} */
            role: "OWNER" | "EDITOR" | "REVIEWER" | "VIEWER" | "AGENT";
            /** @enum {string} */
            status: "ACTIVE" | "INACTIVE";
        };
        AssetPatch: {
            /** Format: int64 */
            expectedVersion: number;
            name?: string;
            quantity?: string;
            /** Format: date */
            serviceDate?: string;
            originalCost?: string;
            inputTax?: string;
            /** Format: int32 */
            usefulLifeMonths?: number;
            residualRate?: string;
            impairmentAmount?: string;
            /** Format: uuid */
            departmentValueId?: string;
            /** Format: uuid */
            acquisitionVoucherId?: string;
            /** Format: uuid */
            assetAccountId?: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId?: string;
            /** Format: uuid */
            depreciationExpenseAccountId?: string;
            /** Format: uuid */
            impairmentAccountId?: string;
            /** Format: uuid */
            clearingAccountId?: string;
            /** Format: uuid */
            disposalGainAccountId?: string;
            /** Format: uuid */
            disposalLossAccountId?: string;
            /** Format: uuid */
            changePeriodId?: string;
            reason?: string;
            note?: string;
        };
        CategoryPatch: {
            /** Format: int64 */
            expectedVersion: number;
            name?: string;
            /** Format: int32 */
            usefulLifeMonths?: number;
            residualRate?: string;
            /** Format: uuid */
            assetAccountId?: string;
            /** Format: uuid */
            accumulatedDepreciationAccountId?: string;
            /** Format: uuid */
            depreciationExpenseAccountId?: string;
            /** Format: uuid */
            impairmentAccountId?: string;
            /** Format: uuid */
            clearingAccountId?: string;
            /** Format: uuid */
            disposalGainAccountId?: string;
            /** Format: uuid */
            disposalLossAccountId?: string;
            status?: string;
        };
        DimensionTypePatch: {
            /** Format: int64 */
            expectedVersion: number;
            name?: string;
            status?: string;
            required?: boolean;
        };
        DimensionValuePatch: {
            /** Format: int64 */
            expectedVersion: number;
            name?: string;
            status?: string;
        };
        AccountPatch: {
            /** Format: int64 */
            expectedVersion: number;
            code?: string;
            name?: string;
            /** Format: uuid */
            parentId?: string;
            category?: string;
            normalBalance?: string;
            status?: string;
            cashFlowRequired?: boolean;
            /** Format: uuid */
            defaultCashFlowItemId?: string;
            quantityEnabled?: boolean;
            unitName?: string;
            dimensionRequirements?: components["schemas"]["AccountDimensionRequirementRequest"][];
        };
        /** @default null */
        CurrentUser: {
            /** Format: uuid */
            id: string;
            issuer: string;
            subject: string;
            displayName: string;
            /** @default  */
            email: string | null;
            /** @enum {string} */
            userType: "HUMAN" | "AGENT";
            status: string;
        };
        /** @default null */
        VoucherRevision: {
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            revision: number;
            action: string;
            /** Format: uuid */
            actorId: string;
            /** @default  */
            reason: string | null;
            /** @default  */
            beforeData: string | null;
            /** @default  */
            afterData: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        /** @default null */
        TrialBalanceLine: {
            /** Format: uuid */
            accountId: string;
            code: string;
            name: string;
            category: string;
            openingDebit: string;
            openingCredit: string;
            periodDebit: string;
            periodCredit: string;
            closingDebit: string;
            closingCredit: string;
            debit: string;
            credit: string;
            balance: string;
        };
        /** @default null */
        LedgerLine: {
            /** Format: uuid */
            voucherId: string;
            voucherNumber: string;
            /** Format: date */
            voucherDate: string;
            accountCode: string;
            accountName: string;
            side: string;
            amount: string;
            /** @default  */
            dimensionKey: string | null;
        };
        /** @default null */
        Check: {
            key: string;
            name: string;
            passed: boolean;
            difference: string;
        };
        /** @default null */
        Group: {
            key: string;
            title: string;
            lines: components["schemas"]["StatutoryStatementLine"][];
        };
        /** @default null */
        StatutoryStatement: {
            reportType: string;
            templateCode: string;
            standardCode: string;
            standardVersion: string;
            periodCode: string;
            primaryColumn: string;
            comparativeColumn: string;
            groups: components["schemas"]["Group"][];
            checks: components["schemas"]["Check"][];
            /** @default  */
            formulaCode: string | null;
            /**
             * Format: int32
             * @default
             */
            formulaVersion: number | null;
        };
        /** @default null */
        StatutoryStatementLine: {
            key: string;
            /** Format: int32 */
            lineNo: number;
            name: string;
            /** Format: int32 */
            indent: number;
            rowType: string;
            primaryAmount: string;
            comparativeAmount: string;
        };
        /** @default null */
        AccountStatement: {
            /** Format: int32 */
            totalLines: number;
            lines: components["schemas"]["StatementLine"][];
            /** @default  */
            formulaCode: string | null;
            /**
             * Format: int32
             * @default
             */
            formulaVersion: number | null;
        };
        /** @default null */
        StatementLine: {
            code: string;
            name: string;
            amount: string;
        };
        /** @default null */
        ReportFormulaWorkspace: {
            code: string;
            name: string;
            kind: string;
            reportType: string;
            templateCode: string;
            /** Format: int32 */
            publishedVersion: number;
            publishedDefinition: unknown;
            draft?: components["schemas"]["ReportFormulaDraft"];
        };
        /** @default null */
        ReportFormulaVersionInfo: {
            /** Format: int32 */
            version: number;
            source: string;
            /** Format: int32 */
            rollbackOfVersion?: number;
            /** Format: uuid */
            createdBy?: string;
            /** Format: date-time */
            createdAt?: string;
            definition: unknown;
        };
        /** @default null */
        ReportFormulaVersionPage: {
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            pageSize: number;
            /** Format: int64 */
            totalItems: number;
            /** Format: int32 */
            totalPages: number;
            items: components["schemas"]["ReportFormulaVersionInfo"][];
        };
        /** @default null */
        PeriodClosingStatus: {
            /**
             * Format: uuid
             * @default
             */
            ledgerId: string;
            /**
             * Format: uuid
             * @default
             */
            periodId: string;
            /** @default  */
            periodCode: string;
            /** @default  */
            steps: components["schemas"]["PeriodClosingStep"][];
            /** @default  */
            blockers: components["schemas"]["PeriodClosingBlocker"][];
            /** @default  */
            trialBalance: components["schemas"]["PeriodClosingTrialBalance"];
            /** @default false */
            canClose: boolean;
        };
        /** @default null */
        PeriodClosingTrialBalance: {
            /** @default  */
            openingDebit: string;
            /** @default  */
            openingCredit: string;
            /** @default  */
            periodDebit: string;
            /** @default  */
            periodCredit: string;
            /** @default  */
            closingDebit: string;
            /** @default  */
            closingCredit: string;
            /** @default  */
            openingDifference: string;
            /** @default  */
            periodDifference: string;
            /** @default  */
            closingDifference: string;
            /** @default false */
            balanced: boolean;
        };
        /** @default null */
        DocumentJob: {
            /**
             * Format: uuid
             * @default
             */
            id: string;
            /**
             * Format: uuid
             * @default
             */
            ledgerId: string;
            /** @default  */
            jobType: string;
            /**
             * Format: uuid
             * @default
             */
            aggregateId: string;
            /** @default  */
            status: string;
            /**
             * Format: int32
             * @default
             */
            attempts: number;
            /**
             * Format: date-time
             * @default
             */
            nextRunAt: string | null;
            /** @default  */
            lockedBy: string | null;
        };
        /** @default null */
        FixedAssetPage: {
            data: components["schemas"]["FixedAsset"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            pageSize: number;
            /** Format: int64 */
            totalItems: number;
            /** Format: int32 */
            totalPages: number;
        };
        /** @default null */
        FixedAssetDepreciationPreview: {
            /** Format: uuid */
            periodId: string;
            periodCode: string;
            totalAmount: string;
            /** Format: int32 */
            eligibleCount: number;
            /** Format: int32 */
            completedCount: number;
            /** Format: int32 */
            pendingCount: number;
            readyToClose: boolean;
            blockers: string[];
            lines: components["schemas"]["FixedAssetDepreciationPreviewLine"][];
        };
        /** @default null */
        FixedAssetDepreciationPreviewLine: {
            /** Format: uuid */
            assetId: string;
            assetCode: string;
            assetName: string;
            amount: string;
            status: string;
            detail: string;
        };
        /** @default null */
        LedgerCashFlowItem: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ledgerId: string;
            code: string;
            name: string;
            status: string;
            template: boolean;
        };
        /** @default null */
        SubLedgerEntry: {
            /** Format: uuid */
            voucherId: string;
            voucherNumber: string;
            /** Format: date */
            voucherDate: string;
            /** Format: uuid */
            postingAccountId: string;
            postingAccountCode: string;
            postingAccountName: string;
            /** @default  */
            summary: string | null;
            debit: string;
            credit: string;
            direction: string;
            balance: string;
        };
        /** @default null */
        SubLedgerPage: {
            periodFrom: string;
            periodTo: string;
            periodCode: string;
            /** Format: uuid */
            accountId: string;
            accountCode: string;
            accountName: string;
            openingDirection: string;
            openingBalance: string;
            data: components["schemas"]["SubLedgerEntry"][];
            periodDebit: string;
            periodCredit: string;
            endingDirection: string;
            endingBalance: string;
            pagination: components["schemas"]["Pagination"];
        };
        /** @default null */
        GeneralLedgerAccount: {
            /** Format: uuid */
            accountId: string;
            accountCode: string;
            accountName: string;
            normalBalance: string;
            openingDirection: string;
            openingBalance: string;
            periodDebit: string;
            periodCredit: string;
            yearDebit: string;
            yearCredit: string;
            endingDirection: string;
            endingBalance: string;
        };
        /** @default null */
        GeneralLedgerPage: {
            periodFrom: string;
            periodTo: string;
            periodCode: string;
            data: components["schemas"]["GeneralLedgerAccount"][];
            pagination: components["schemas"]["Pagination"];
        };
        /** @default null */
        AuditPage: {
            items: components["schemas"]["Entry"][];
            /** @default  */
            nextCursor: string | null;
            hasMore: boolean;
        };
        /** @default null */
        Entry: {
            /** Format: uuid */
            id: string;
            aggregateType: string;
            /** Format: uuid */
            aggregateId: string;
            /** Format: int32 */
            revision: number;
            action: string;
            /** Format: uuid */
            actorId: string;
            /** @default  */
            reason: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        /** @default null */
        NextAccountCodeResponse: {
            code: string;
        };
        /** @default null */
        LedgerAccountSearchResult: {
            account: components["schemas"]["Account"];
            /** @default  */
            parent: components["schemas"]["LedgerAccountSummary"];
            children: components["schemas"]["LedgerAccountSummary"][];
        };
        /** @default null */
        LedgerAccountSummary: {
            /** Format: uuid */
            id: string;
            code: string;
            name: string;
            status: string;
        };
        /** @default null */
        AccountingStandardAccount: {
            code: string;
            standardAccountKey: string;
            name: string;
            /** @default  */
            parentCode: string | null;
            category: string;
            normalBalance: string;
            cashFlowRequired: boolean;
            quantityEnabled: boolean;
            /** @default  */
            unitName: string | null;
        };
        /** @default null */
        AccountingStandardCashFlowItem: {
            code: string;
            name: string;
        };
        /** @default null */
        AccountingStandardDimensionType: {
            code: string;
            name: string;
            required: boolean;
        };
        /** @default null */
        AccountingStandardFormula: {
            code: string;
            name: string;
            definition: components["schemas"]["JsonNode"];
        };
        /** @default null */
        AccountingStandardKey: {
            key: string;
            legacyCodes: string[];
        };
        /** @default null */
        AccountingStandardPackage: {
            code: string;
            version: string;
            name: string;
            /** Format: date */
            effectiveDate: string;
            accountCodeRule: components["schemas"]["AccountCodeRule"];
            standardAccountKeys: components["schemas"]["AccountingStandardKey"][];
            accounts: components["schemas"]["AccountingStandardAccount"][];
            formulas: components["schemas"]["AccountingStandardFormula"][];
            cashFlowItems: components["schemas"]["AccountingStandardCashFlowItem"][];
            dimensionTypes: components["schemas"]["AccountingStandardDimensionType"][];
        };
        JsonNode: unknown;
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    update: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VoucherUpdateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    delete: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReportFormulaDraftUpdate"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaDraft"];
                };
            };
        };
    };
    createDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaDraft"];
                };
            };
        };
    };
    deleteDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listOpeningBalances: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OpeningBalanceResponse"][];
                };
            };
        };
    };
    replaceOpeningBalances: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OpeningBalances"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OpeningBalanceResponse"][];
                };
            };
        };
    };
    decide: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                importId: string;
                rowNo: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountImportDecision"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountImportPreview"];
                };
            };
        };
    };
    getAccountCodeRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountCodeRule"];
                };
            };
        };
    };
    updateAccountCodeRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountCodeRuleUpdate"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountCodeRule"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerResponse"][];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LedgerCreateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerResponse"];
                };
            };
        };
    };
    list_1: {
        parameters: {
            query?: {
                periodCode?: string;
                startDate?: string;
                endDate?: string;
                keyword?: string;
                limit?: number;
                offset?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    /** @description Total number of vouchers matching the supplied filters */
                    "X-Total-Count"?: unknown[];
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"][];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VoucherCreateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    validate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    submit: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    reject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Comment"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    post: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    approve: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Comment"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    restoreRevision: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
                revision: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    publish: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReportFormulaPublishRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaPublishResult"];
                };
            };
        };
    };
    rollback: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
                version: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReportFormulaRollbackRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaRollbackResult"];
                };
            };
        };
    };
    resetDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReportFormulaDraftReset"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaDraft"];
                };
            };
        };
    };
    preview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReportFormulaPreviewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaPreviewResult"];
                };
            };
        };
    };
    reopenPeriod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                periodId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PeriodAction"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Period"];
                };
            };
        };
    };
    closePeriod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                periodId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PeriodAction"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Period"];
                };
            };
        };
    };
    reset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                periodId: string;
                step: "DEPRECIATION" | "EXPENSE_TRANSFER" | "REVENUE_TRANSFER" | "YEAR_END_PROFIT_TRANSFER";
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Reset"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PeriodClosingStep"];
                };
            };
        };
    };
    generate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                periodId: string;
                step: "DEPRECIATION" | "EXPENSE_TRANSFER" | "REVENUE_TRANSFER" | "YEAR_END_PROFIT_TRANSFER";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PeriodClosingStep"];
                };
            };
        };
    };
    importOpeningBalances: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                    reason?: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OpeningBalanceResponse"][];
                };
            };
        };
    };
    confirmOpeningBalances: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: number;
                    };
                };
            };
        };
    };
    listMembers: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"][];
                };
            };
        };
    };
    addMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddMember"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"];
                };
            };
        };
    };
    assets: {
        parameters: {
            query?: {
                periodId?: string;
                status?: string;
                categoryId?: string;
                departmentValueId?: string;
                search?: string;
                page?: number;
                pageSize?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetPage"];
                };
            };
        };
    };
    createAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssetCreate"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAsset"];
                };
            };
        };
    };
    dispose: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FixedAssetDisposalRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetDisposalResponse"];
                };
            };
        };
    };
    copyAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAsset"];
                };
            };
        };
    };
    cancelDisposal: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DisposalCancellation"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAsset"];
                };
            };
        };
    };
    importAssets: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetImportResult"];
                };
            };
        };
    };
    regenerate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DepreciationAction"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetDepreciationRun"];
                };
            };
        };
    };
    generate_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DepreciationAction"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetDepreciationRun"];
                };
            };
        };
    };
    categories: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetCategory"][];
                };
            };
        };
    };
    createCategory: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CategoryCreate"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetCategory"];
                };
            };
        };
    };
    query: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FinanceQueryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FinanceQueryLine"][];
                };
            };
        };
    };
    list_2: {
        parameters: {
            query?: {
                limit?: number;
                offset?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentResponse"][];
                };
            };
        };
    };
    upload: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentResponse"];
                };
            };
        };
    };
    extract: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                documentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentExtraction"];
                };
            };
        };
    };
    createVoucherDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                documentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Voucher"];
                };
            };
        };
    };
    listDimensionValues: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionValuesBatchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DimensionValuesBatchResponse"];
                };
            };
        };
    };
    listDimensionTypes: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DimensionType"][];
                };
            };
        };
    };
    createDimensionType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionTypeCreate"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DimensionType"];
                };
            };
        };
    };
    listDimensionValues_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                typeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerDimensionValue"][];
                };
            };
        };
    };
    createDimensionValue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                typeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionValueCreate"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerDimensionValue"];
                };
            };
        };
    };
    importKingdee: {
        parameters: {
            query?: never;
            header?: {
                "Idempotency-Key"?: string;
            };
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["KingdeeImportResult"];
                };
            };
        };
    };
    dimensionLedger: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionLedgerQuery"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DimensionLedgerPage"];
                };
            };
        };
    };
    request: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BalanceRebuildCreateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BalanceRebuildJob"];
                };
            };
        };
    };
    listAccounts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Account"][];
                };
            };
        };
    };
    createAccount: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountCreate"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Account"];
                };
            };
        };
    };
    preview_1: {
        parameters: {
            query: {
                format: "STANDARD" | "KINGDEE";
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountImportPreview"];
                };
            };
        };
    };
    commit: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                importId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountImportPreview"];
                };
            };
        };
    };
    restore: {
        parameters: {
            query?: {
                name?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerResponse"];
                };
            };
        };
    };
    restoreUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AdminUser"];
                };
            };
        };
    };
    restoreLedger: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AdminLedger"];
                };
            };
        };
    };
    get_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerResponse"];
                };
            };
        };
    };
    update_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Rename"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerResponse"];
                };
            };
        };
    };
    settings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PeriodClosingSettings"];
                };
            };
        };
    };
    updateSettings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SettingsPatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PeriodClosingSettings"];
                };
            };
        };
    };
    removeMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateMember"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"];
                };
            };
        };
    };
    asset: {
        parameters: {
            query?: {
                periodId?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAsset"];
                };
            };
        };
    };
    deleteAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssetPatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAsset"];
                };
            };
        };
    };
    category: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                categoryId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetCategory"];
                };
            };
        };
    };
    updateCategory: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                categoryId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CategoryPatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetCategory"];
                };
            };
        };
    };
    updateDimensionType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                typeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionTypePatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DimensionType"];
                };
            };
        };
    };
    updateDimensionValue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                typeId: string;
                valueId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DimensionValuePatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerDimensionValue"];
                };
            };
        };
    };
    getAccount: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                accountId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Account"];
                };
            };
        };
    };
    deleteAccount: {
        parameters: {
            query: {
                expectedVersion: number;
            };
            header?: never;
            path: {
                ledgerId: string;
                accountId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateAccount: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                accountId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountPatch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Account"];
                };
            };
        };
    };
    me: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CurrentUser"];
                };
            };
        };
    };
    revisions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                voucherId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VoucherRevision"][];
                };
            };
        };
    };
    role: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: string;
                    };
                };
            };
        };
    };
    trialBalance: {
        parameters: {
            query?: {
                periodCode?: string;
                periodFrom?: string;
                periodTo?: string;
                includeParents?: boolean;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TrialBalanceLine"][];
                };
            };
        };
    };
    subLedger: {
        parameters: {
            query?: {
                periodCode?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerLine"][];
                };
            };
        };
    };
    statutory: {
        parameters: {
            query: {
                periodCode: string;
            };
            header?: never;
            path: {
                ledgerId: string;
                reportType: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["StatutoryStatement"];
                };
            };
        };
    };
    incomeStatement: {
        parameters: {
            query?: {
                periodCode?: string;
                periodFrom?: string;
                periodTo?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountStatement"];
                };
            };
        };
    };
    generalLedger: {
        parameters: {
            query?: {
                periodCode?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerLine"][];
                };
            };
        };
    };
    balanceSheet: {
        parameters: {
            query?: {
                periodCode?: string;
                periodFrom?: string;
                periodTo?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountStatement"];
                };
            };
        };
    };
    workspace: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaWorkspace"];
                };
            };
        };
    };
    versions: {
        parameters: {
            query?: {
                page?: number;
                pageSize?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaVersionPage"];
                };
            };
        };
    };
    version: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                code: string;
                version: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportFormulaVersionInfo"];
                };
            };
        };
    };
    listPeriods: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Period"][];
                };
            };
        };
    };
    status: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                periodId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PeriodClosingStatus"];
                };
            };
        };
    };
    findMemberCandidates: {
        parameters: {
            query: {
                email: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CurrentUser"][];
                };
            };
        };
    };
    get_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                jobId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentJob"];
                };
            };
        };
    };
    importTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": string;
                };
            };
        };
    };
    runs: {
        parameters: {
            query: {
                periodId: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetDepreciationRun"][];
                };
            };
        };
    };
    preview_2: {
        parameters: {
            query: {
                periodId: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FixedAssetDepreciationPreview"];
                };
            };
        };
    };
    get_3: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                documentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentResponse"];
                };
            };
        };
    };
    extractions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                documentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DocumentExtraction"][];
                };
            };
        };
    };
    content: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                documentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    exportKingdee: {
        parameters: {
            query?: {
                mergeEntries?: boolean;
                startDate?: string;
                endDate?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": string;
                };
            };
        };
    };
    listCashFlowItems: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerCashFlowItem"][];
                };
            };
        };
    };
    subLedger_1: {
        parameters: {
            query: {
                periodCode?: string;
                periodFrom?: string;
                periodTo?: string;
                accountId: string;
                page?: number;
                pageSize?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SubLedgerPage"];
                };
            };
        };
    };
    generalLedger_1: {
        parameters: {
            query?: {
                periodCode?: string;
                periodFrom?: string;
                periodTo?: string;
                page?: number;
                pageSize?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GeneralLedgerPage"];
                };
            };
        };
    };
    find: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                jobId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BalanceRebuildJob"];
                };
            };
        };
    };
    backup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    list_3: {
        parameters: {
            query?: {
                limit?: number;
                cursor?: string;
                aggregateType?: string;
                aggregateId?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AuditPage"];
                };
            };
        };
    };
    nextChildAccountCode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                accountId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NextAccountCodeResponse"];
                };
            };
        };
    };
    searchAccounts: {
        parameters: {
            query: {
                query: string;
                matchMode?: "EXACT" | "FUZZY";
                limit?: number;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerAccountSearchResult"][];
                };
            };
        };
    };
    get_4: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
                importId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountImportPreview"];
                };
            };
        };
    };
    template: {
        parameters: {
            query: {
                format: "STANDARD" | "KINGDEE";
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    export: {
        parameters: {
            query: {
                format: "STANDARD" | "KINGDEE";
                createdInPeriodId?: string;
            };
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    listUsers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AdminUser"][];
                };
            };
        };
    };
    listLedgers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AdminLedger"][];
                };
            };
        };
    };
    list_4: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountingStandardPackage"][];
                };
            };
        };
    };
    get_5: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: string;
                version: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccountingStandardPackage"];
                };
            };
        };
    };
    deleteUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    deleteLedger: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
