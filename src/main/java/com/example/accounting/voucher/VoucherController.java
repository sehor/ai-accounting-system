package com.example.accounting.voucher;

import com.example.accounting.identity.CurrentUserResolver;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/vouchers")
public class VoucherController {

    private final CurrentUserResolver currentUserResolver;
    private final VoucherService voucherService;

    public VoucherController(CurrentUserResolver currentUserResolver, VoucherService voucherService) {
        this.currentUserResolver = currentUserResolver;
        this.voucherService = voucherService;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", headers = @Header(
            name = "X-Total-Count",
            description = "Total number of vouchers matching the period filter",
            schema = @Schema(type = "integer", format = "int64")))
    public List<VoucherResponses.Voucher> list(HttpServletRequest request, HttpServletResponse response,
                                                @PathVariable UUID ledgerId,
                                                @RequestParam(required = false) String periodCode,
                                                @RequestParam(defaultValue = "100") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        UUID actorId = user(request);
        List<VoucherResponses.Voucher> result = voucherService.list(
                actorId, ledgerId, periodCode, limit, offset);
        response.setHeader("X-Total-Count", Long.toString(voucherService.count(actorId, ledgerId, periodCode)));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
        return result;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherResponses.Voucher create(HttpServletRequest request, @PathVariable UUID ledgerId,
                                            @Valid @RequestBody VoucherRequests.Create body) {
        return voucherService.create(user(request), ledgerId, body, request.getHeader("Idempotency-Key"));
    }

    @GetMapping("/{voucherId}")
    public VoucherResponses.Voucher get(HttpServletRequest request, @PathVariable UUID ledgerId,
                                        @PathVariable UUID voucherId) {
        return voucherService.find(user(request), ledgerId, voucherId);
    }

    @PutMapping("/{voucherId}")
    public VoucherResponses.Voucher update(HttpServletRequest request, @PathVariable UUID ledgerId,
                                           @PathVariable UUID voucherId,
                                           @Valid @RequestBody VoucherRequests.Update body) {
        return voucherService.update(user(request), ledgerId, voucherId, body);
    }

    @PostMapping("/{voucherId}:validate")
    public VoucherResponses.Voucher validate(HttpServletRequest request, @PathVariable UUID ledgerId,
                                             @PathVariable UUID voucherId) {
        return voucherService.validate(user(request), ledgerId, voucherId);
    }

    @PostMapping("/{voucherId}:submit")
    public VoucherResponses.Voucher submit(HttpServletRequest request, @PathVariable UUID ledgerId,
                                           @PathVariable UUID voucherId) {
        return voucherService.submit(user(request), ledgerId, voucherId);
    }

    @PostMapping("/{voucherId}:approve")
    public VoucherResponses.Voucher approve(HttpServletRequest request, @PathVariable UUID ledgerId,
                                            @PathVariable UUID voucherId,
                                            @Valid @RequestBody VoucherRequests.Comment body) {
        return voucherService.approve(user(request), ledgerId, voucherId, body.comment());
    }

    @PostMapping("/{voucherId}:reject")
    public VoucherResponses.Voucher reject(HttpServletRequest request, @PathVariable UUID ledgerId,
                                           @PathVariable UUID voucherId,
                                           @Valid @RequestBody VoucherRequests.Comment body) {
        return voucherService.reject(user(request), ledgerId, voucherId, body.comment());
    }

    @PostMapping("/{voucherId}:post")
    public VoucherResponses.Voucher post(HttpServletRequest request, @PathVariable UUID ledgerId,
                                         @PathVariable UUID voucherId) {
        return voucherService.post(user(request), ledgerId, voucherId);
    }

    @DeleteMapping("/{voucherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID voucherId) {
        voucherService.delete(user(request), ledgerId, voucherId);
    }

    @PostMapping("/{voucherId}:restore-deleted")
    public VoucherResponses.Voucher restoreDeleted(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                    @PathVariable UUID voucherId) {
        return voucherService.restoreDeleted(user(request), ledgerId, voucherId);
    }

    @GetMapping("/{voucherId}/revisions")
    public List<VoucherResponses.Revision> revisions(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                     @PathVariable UUID voucherId) {
        return voucherService.listRevisions(user(request), ledgerId, voucherId);
    }

    @PostMapping("/{voucherId}/revisions/{revision}:restore")
    public VoucherResponses.Voucher restoreRevision(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                    @PathVariable UUID voucherId, @PathVariable int revision) {
        return voucherService.restoreRevision(user(request), ledgerId, voucherId, revision);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
