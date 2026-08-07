package com.example.accounting.administration;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin")
public class AdministrationController {

    private final CurrentUserResolver users;
    private final AdministrationService administration;

    public AdministrationController(CurrentUserResolver users, AdministrationService administration) {
        this.users = users;
        this.administration = administration;
    }

    @GetMapping("/users")
    public List<AdminResponses.User> listUsers(HttpServletRequest request) {
        return administration.listUsers(users.resolve(request));
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(HttpServletRequest request, @PathVariable UUID userId) {
        administration.deleteUser(users.resolve(request), userId);
    }

    @PostMapping("/users/{userId}:restore")
    public AdminResponses.User restoreUser(HttpServletRequest request, @PathVariable UUID userId) {
        return administration.restoreUser(users.resolve(request), userId);
    }

    @GetMapping("/ledgers")
    public List<AdminResponses.Ledger> listLedgers(HttpServletRequest request) {
        return administration.listLedgers(users.resolve(request));
    }

    @DeleteMapping("/ledgers/{ledgerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLedger(HttpServletRequest request, @PathVariable UUID ledgerId) {
        administration.deleteLedger(users.resolve(request), ledgerId);
    }

    @PostMapping("/ledgers/{ledgerId}:restore")
    public AdminResponses.Ledger restoreLedger(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return administration.restoreLedger(users.resolve(request), ledgerId);
    }
}
