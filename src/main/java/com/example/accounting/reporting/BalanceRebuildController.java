package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/balance-rebuilds")
public class BalanceRebuildController {

    private final CurrentUserResolver users;
    private final BalanceRebuildService rebuilds;

    public BalanceRebuildController(CurrentUserResolver users, BalanceRebuildService rebuilds) {
        this.users = users;
        this.rebuilds = rebuilds;
    }

    @PostMapping
    public ResponseEntity<BalanceRebuildResponses.Job> request(HttpServletRequest request,
                                                                @PathVariable UUID ledgerId,
                                                                @Valid @RequestBody BalanceRebuildRequests.Create body) {
        return ResponseEntity.accepted().body(rebuilds.request(users.resolve(request), ledgerId, body));
    }

    @GetMapping("/{jobId}")
    public BalanceRebuildResponses.Job find(HttpServletRequest request, @PathVariable UUID ledgerId,
                                             @PathVariable UUID jobId) {
        return rebuilds.find(users.resolve(request), ledgerId, jobId);
    }
}
