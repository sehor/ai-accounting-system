package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static com.example.accounting.ledger.LedgerResponses.Ledger;
import static com.example.accounting.ledger.LedgerResponses.Member;

@RestController
@RequestMapping("/v1/ledgers")
public class LedgerController {

    private final CurrentUserResolver currentUserResolver;
    private final LedgerService ledgerService;

    public LedgerController(CurrentUserResolver currentUserResolver, LedgerService ledgerService) {
        this.currentUserResolver = currentUserResolver;
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public List<Ledger> list(HttpServletRequest request) {
        return ledgerService.list(user(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Ledger create(HttpServletRequest request, @Valid @RequestBody LedgerRequests.Create body) {
        return ledgerService.create(currentUserResolver.resolveUser(request), body);
    }

    @GetMapping("/{ledgerId}")
    public Ledger get(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ledgerService.findLedger(user(request), ledgerId);
    }

    @GetMapping("/{ledgerId}/members")
    public List<Member> listMembers(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ledgerService.listMembers(user(request), ledgerId);
    }

    @GetMapping("/{ledgerId}/accounts")
    public List<LedgerResponses.Account> listAccounts(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ledgerService.listAccounts(user(request), ledgerId);
    }

    @GetMapping("/{ledgerId}/periods")
    public List<LedgerResponses.Period> listPeriods(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ledgerService.listPeriods(user(request), ledgerId);
    }

    @PostMapping("/{ledgerId}/periods/{periodId}:close")
    public LedgerResponses.Period closePeriod(HttpServletRequest request, @PathVariable UUID ledgerId,
                                               @PathVariable UUID periodId,
                                               @Valid @RequestBody LedgerRequests.PeriodAction body) {
        return ledgerService.closePeriod(user(request), ledgerId, periodId, body);
    }

    @PostMapping("/{ledgerId}/periods/{periodId}:reopen")
    public LedgerResponses.Period reopenPeriod(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                @PathVariable UUID periodId,
                                                @Valid @RequestBody LedgerRequests.PeriodAction body) {
        return ledgerService.reopenPeriod(user(request), ledgerId, periodId, body);
    }

    @GetMapping("/{ledgerId}/dimension-types")
    public List<LedgerResponses.DimensionType> listDimensionTypes(HttpServletRequest request,
                                                                    @PathVariable UUID ledgerId) {
        return ledgerService.listDimensionTypes(user(request), ledgerId);
    }

    @PostMapping("/{ledgerId}/dimension-types")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.DimensionType createDimensionType(HttpServletRequest request,
                                                              @PathVariable UUID ledgerId,
                                                              @Valid @RequestBody LedgerRequests.DimensionTypeCreate body) {
        return ledgerService.createDimensionType(user(request), ledgerId, body);
    }

    @GetMapping("/{ledgerId}/dimension-types/{typeId}/values")
    public List<LedgerResponses.DimensionValue> listDimensionValues(HttpServletRequest request,
                                                                      @PathVariable UUID ledgerId,
                                                                      @PathVariable UUID typeId) {
        return ledgerService.listDimensionValues(user(request), ledgerId, typeId);
    }

    @PostMapping("/{ledgerId}/dimension-types/{typeId}/values")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.DimensionValue createDimensionValue(HttpServletRequest request,
                                                                @PathVariable UUID ledgerId,
                                                                @PathVariable UUID typeId,
                                                                @Valid @RequestBody LedgerRequests.DimensionValueCreate body) {
        return ledgerService.createDimensionValue(user(request), ledgerId, typeId, body);
    }

    @GetMapping("/{ledgerId}/opening-balances")
    public List<LedgerResponses.OpeningBalance> listOpeningBalances(HttpServletRequest request,
                                                                      @PathVariable UUID ledgerId) {
        return ledgerService.listOpeningBalances(user(request), ledgerId);
    }

    @PutMapping("/{ledgerId}/opening-balances")
    public List<LedgerResponses.OpeningBalance> replaceOpeningBalances(HttpServletRequest request,
                                                                         @PathVariable UUID ledgerId,
                                                                         @Valid @RequestBody LedgerRequests.OpeningBalances body) {
        return ledgerService.replaceOpeningBalances(user(request), ledgerId, body.lines());
    }

    @PostMapping(value = "/{ledgerId}/opening-balances:import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<LedgerResponses.OpeningBalance> importOpeningBalances(HttpServletRequest request,
                                                                        @PathVariable UUID ledgerId,
                                                                        @RequestPart("file") MultipartFile file)
            throws java.io.IOException {
        return ledgerService.importOpeningBalances(user(request), ledgerId, file.getInputStream());
    }

    @PostMapping("/{ledgerId}/opening-balances:confirm")
    public java.util.Map<String, Integer> confirmOpeningBalances(HttpServletRequest request,
                                                                  @PathVariable UUID ledgerId) {
        return java.util.Map.of("confirmedCount", ledgerService.confirmOpeningBalances(user(request), ledgerId));
    }

    @PostMapping("/{ledgerId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public Member addMember(HttpServletRequest request, @PathVariable UUID ledgerId,
                            @Valid @RequestBody LedgerRequests.AddMember body) {
        return ledgerService.addMember(user(request), ledgerId, body);
    }

    @PatchMapping("/{ledgerId}/members/{userId}")
    public Member updateMember(HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID userId,
                               @Valid @RequestBody LedgerRequests.UpdateMember body) {
        return ledgerService.updateMember(user(request), ledgerId, userId, body);
    }

    @DeleteMapping("/{ledgerId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID userId) {
        ledgerService.removeMember(user(request), ledgerId, userId);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
