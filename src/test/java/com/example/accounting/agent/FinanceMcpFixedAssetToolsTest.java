package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinanceMcpFixedAssetToolsTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID ledgerId = UUID.randomUUID();
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private ValidatorFactory factory;
    private FinanceMcpTools tools;
    private FixedAssetService assets;

    @BeforeEach
    void setUp() throws Exception {
        factory = Validation.buildDefaultValidatorFactory();
        var constructor = FinanceMcpTools.class.getConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = constructor.getParameterTypes()[i];
            args[i] = type == Validator.class ? factory.getValidator() : mock(type);
            dependencies.put(type, args[i]);
        }
        tools = (FinanceMcpTools) constructor.newInstance(args);
        assets = (FixedAssetService) dependencies.get(FixedAssetService.class);
        when(((CurrentUserResolver) dependencies.get(CurrentUserResolver.class))
                .resolveAuthenticatedUser()).thenReturn(actorId);
    }

    @AfterEach
    void closeValidator() {
        factory.close();
    }

    @Test
    void createsCardWithAccountingOverridesWithoutCreatingVoucher() {
        var request = request(36);
        tools.createFixedAsset(ledgerId, request);
        verify(assets).createAsset(actorId, ledgerId, request);
        verifyNoInteractions(dependencies.get(VoucherService.class));
        assertThat(request.departmentValueId()).isNotNull();
        assertThat(request.acquisitionVoucherId()).isNotNull();
        assertThat(request.depreciationExpenseAccountId()).isNotNull();
    }

    @Test
    void invalidAndMissingRequestsNeverReachAssetService() {
        assertThatThrownBy(() -> tools.createFixedAsset(ledgerId, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("FIXED_ASSET_REQUEST_INVALID"));
        assertThatThrownBy(() -> tools.createFixedAsset(ledgerId, request(0)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("FIXED_ASSET_REQUEST_INVALID"));
        verifyNoInteractions(assets);
    }

    @Test
    void serviceAuthorizationErrorsRemainUnchanged() {
        var request = request(36);
        var denied = new ApiProblemException(403, "LEDGER_FORBIDDEN", "Forbidden", "Not allowed", false);
        when(assets.createAsset(actorId, ledgerId, request)).thenThrow(denied);
        assertThatThrownBy(() -> tools.createFixedAsset(ledgerId, request)).isSameAs(denied);
    }

    @Test
    void listUsesDefaultsAndForwardsAllFilters() {
        tools.listFixedAssets(ledgerId, null, null, null, null, null, null, null);
        verify(assets).listAssets(actorId, ledgerId, null, null, null, null, null, 1, 50);
        UUID period = UUID.randomUUID(), category = UUID.randomUUID(), department = UUID.randomUUID();
        tools.listFixedAssets(ledgerId, period, "ACTIVE", category, department, "ThinkPad", 2, 25);
        verify(assets).listAssets(actorId, ledgerId, period, "ACTIVE", category, department, "ThinkPad", 2, 25);
    }

    @Test
    void readsCardInRequestedLedgerAndPeriod() {
        UUID assetId = UUID.randomUUID(), periodId = UUID.randomUUID();
        tools.getFixedAsset(ledgerId, assetId, periodId);
        verify(assets).findAsset(actorId, ledgerId, assetId, periodId);
        tools.listFixedAssetCategories(ledgerId);
        verify(assets).listCategories(actorId, ledgerId);
    }

    @Test
    void exportsExistingServiceTemplate() {
        byte[] bytes = new byte[] {1, 2, 3};
        when(assets.importTemplate(actorId, ledgerId)).thenReturn(bytes);
        var result = tools.exportFixedAssetTemplate(ledgerId);
        assertThat(result.fileName()).isEqualTo("fixed-assets-template.xlsx");
        assertThat(Base64.getDecoder().decode(result.base64Content())).isEqualTo(bytes);
    }

    @Test
    void schemaPreservesOptionalCardFieldsAndPatchVersion() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var create = mapper.readTree(org.springframework.ai.util.json.schema.JsonSchemaGenerator
                .generateForType(FixedAssetRequests.AssetCreate.class));
        var required = new java.util.ArrayList<String>();
        create.path("required").forEach(node -> required.add(node.asText()));
        assertThat(required).contains("categoryId", "code", "originalCost", "serviceDate")
                .doesNotContain("departmentValueId", "acquisitionVoucherId", "impairmentAccountId", "note");
        var patch = mapper.readTree(org.springframework.ai.util.json.schema.JsonSchemaGenerator
                .generateForType(FixedAssetRequests.AssetPatch.class));
        assertThat(patch.path("required").toString()).isEqualTo("[\"expectedVersion\"]");
        var category = mapper.readTree(org.springframework.ai.util.json.schema.JsonSchemaGenerator
                .generateForType(FixedAssetRequests.CategoryCreate.class));
        assertThat(category.path("required").toString()).doesNotContain("impairmentAccountId");
    }

    private FixedAssetRequests.AssetCreate request(int life) {
        return new FixedAssetRequests.AssetCreate(
                UUID.randomUUID(), "NOTEBOOK-001", "ThinkPad E16", BigDecimal.ONE,
                LocalDate.of(2026, 9, 30), new BigDecimal("5840.71"), new BigDecimal("759.29"),
                life, new BigDecimal("5"), BigDecimal.ZERO, 0, BigDecimal.ZERO,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Existing acquisition voucher");
    }
}
