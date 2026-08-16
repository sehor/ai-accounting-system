package com.example.accounting.fixedasset;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}")
public class FixedAssetController {

    private final CurrentUserResolver currentUserResolver;
    private final FixedAssetService fixedAssets;
    private final FixedAssetDisposalReversalCommand disposalReversals;

    public FixedAssetController(CurrentUserResolver currentUserResolver, FixedAssetService fixedAssets,
                                FixedAssetDisposalReversalCommand disposalReversals) {
        this.currentUserResolver = currentUserResolver;
        this.fixedAssets = fixedAssets;
        this.disposalReversals = disposalReversals;
    }

    @GetMapping("/fixed-asset-categories")
    public List<FixedAssetResponses.Category> categories(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return fixedAssets.listCategories(user(request), ledgerId);
    }

    @GetMapping("/fixed-asset-categories/{categoryId}")
    public FixedAssetResponses.Category category(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                 @PathVariable UUID categoryId) {
        return fixedAssets.findCategory(user(request), ledgerId, categoryId);
    }

    @PostMapping("/fixed-asset-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public FixedAssetResponses.Category createCategory(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                       @Valid @RequestBody FixedAssetRequests.CategoryCreate body) {
        return fixedAssets.createCategory(user(request), ledgerId, body);
    }

    @PatchMapping("/fixed-asset-categories/{categoryId}")
    public FixedAssetResponses.Category updateCategory(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                       @PathVariable UUID categoryId,
                                                       @Valid @RequestBody FixedAssetRequests.CategoryPatch body) {
        return fixedAssets.updateCategory(user(request), ledgerId, categoryId, body);
    }

    @GetMapping("/fixed-assets")
    public FixedAssetResponses.Page assets(HttpServletRequest request, @PathVariable UUID ledgerId,
                                           @RequestParam(required = false) UUID periodId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) UUID categoryId,
                                           @RequestParam(required = false) UUID departmentValueId,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "50") int pageSize) {
        return fixedAssets.listAssets(user(request), ledgerId, periodId, status, categoryId, departmentValueId,
                search, page, pageSize);
    }

    @GetMapping("/fixed-assets/{assetId}")
    public FixedAssetResponses.Asset asset(HttpServletRequest request, @PathVariable UUID ledgerId,
                                           @PathVariable UUID assetId, @RequestParam(required = false) UUID periodId) {
        return fixedAssets.findAsset(user(request), ledgerId, assetId, periodId);
    }

    @PostMapping("/fixed-assets")
    @ResponseStatus(HttpStatus.CREATED)
    public FixedAssetResponses.Asset createAsset(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                 @Valid @RequestBody FixedAssetRequests.AssetCreate body) {
        return fixedAssets.createAsset(user(request), ledgerId, body);
    }

    @PatchMapping("/fixed-assets/{assetId}")
    public FixedAssetResponses.Asset updateAsset(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                 @PathVariable UUID assetId,
                                                 @Valid @RequestBody FixedAssetRequests.AssetPatch body) {
        return fixedAssets.updateAsset(user(request), ledgerId, assetId, body);
    }

    @PostMapping("/fixed-assets/{assetId}:copy")
    public FixedAssetResponses.Asset copyAsset(HttpServletRequest request, @PathVariable UUID ledgerId,
                                               @PathVariable UUID assetId) {
        return fixedAssets.copyAsset(user(request), ledgerId, assetId);
    }

    @DeleteMapping("/fixed-assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAsset(HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID assetId) {
        fixedAssets.deleteAsset(user(request), ledgerId, assetId);
    }

    @GetMapping(value = "/fixed-assets/import-template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> importTemplate(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header("Content-Disposition", "attachment; filename=fixed-assets-template.xlsx")
                .body(fixedAssets.importTemplate(user(request), ledgerId));
    }

    @PostMapping(value = "/fixed-assets/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FixedAssetResponses.ImportResult importAssets(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                         @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return fixedAssets.importAssets(user(request), ledgerId, file);
    }

    @GetMapping("/fixed-asset-depreciation/preview")
    public FixedAssetResponses.DepreciationPreview preview(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                           @RequestParam UUID periodId) {
        return fixedAssets.previewDepreciation(user(request), ledgerId, periodId);
    }

    @PostMapping("/fixed-asset-depreciation:generate")
    public FixedAssetResponses.DepreciationRun generate(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                        @Valid @RequestBody FixedAssetRequests.DepreciationAction body) {
        return fixedAssets.generateDepreciation(user(request), ledgerId, body);
    }

    @PostMapping("/fixed-asset-depreciation:regenerate")
    public FixedAssetResponses.DepreciationRun regenerate(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                          @Valid @RequestBody FixedAssetRequests.DepreciationAction body) {
        return fixedAssets.regenerateDepreciation(user(request), ledgerId, body);
    }

    @GetMapping("/fixed-asset-depreciation/runs")
    public List<FixedAssetResponses.DepreciationRun> runs(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                          @RequestParam UUID periodId) {
        return fixedAssets.listDepreciationRuns(user(request), ledgerId, periodId);
    }

    @PostMapping("/fixed-assets/{assetId}:dispose")
    public FixedAssetResponses.Disposal dispose(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                @PathVariable UUID assetId,
                                                @Valid @RequestBody FixedAssetRequests.Disposal body) {
        return fixedAssets.disposeAsset(user(request), ledgerId, assetId, body);
    }

    @PostMapping("/fixed-assets/{assetId}:cancel-disposal")
    public FixedAssetResponses.Asset cancelDisposal(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                    @PathVariable UUID assetId,
                                                    @Valid @RequestBody FixedAssetRequests.DisposalCancellation body) {
        return disposalReversals.cancelDisposal(
                user(request), ledgerId, assetId, body.expectedVersion(), body.reason());
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
