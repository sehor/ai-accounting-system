package com.example.accounting.documents;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/jobs")
public class JobController {

    private final CurrentUserResolver currentUserResolver;
    private final JobService jobService;

    public JobController(CurrentUserResolver currentUserResolver, JobService jobService) {
        this.currentUserResolver = currentUserResolver;
        this.jobService = jobService;
    }

    @GetMapping("/{jobId}")
    public JobResponses.Job get(HttpServletRequest request, @PathVariable UUID ledgerId,
                                @PathVariable UUID jobId) {
        return jobService.find(currentUserResolver.resolve(request), ledgerId, jobId);
    }
}
