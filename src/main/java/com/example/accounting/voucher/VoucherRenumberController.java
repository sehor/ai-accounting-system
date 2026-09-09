package com.example.accounting.voucher;

import com.example.accounting.identity.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/vouchers")
public class VoucherRenumberController {
    private final CurrentUserResolver users;
    private final VoucherRenumberService service;

    public VoucherRenumberController(CurrentUserResolver users, VoucherRenumberService service) {
        this.users = users;
        this.service = service;
    }

    @PostMapping("/renumber:preview")
    @Operation(operationId = "previewVoucherRenumbering")
    public VoucherRenumbering.Preview preview(HttpServletRequest http, @PathVariable UUID ledgerId,
                                              @Valid @RequestBody VoucherRenumbering.Request request) {
        return service.preview(users.resolve(http), ledgerId, request);
    }

    @PostMapping("/renumber:execute")
    @Operation(operationId = "executeVoucherRenumbering")
    public VoucherRenumbering.Result execute(HttpServletRequest http, @PathVariable UUID ledgerId,
                                              @Valid @RequestBody VoucherRenumbering.ExecuteRequest request) {
        return service.execute(users.resolve(http), ledgerId, request, http.getHeader("Idempotency-Key"));
    }
}
