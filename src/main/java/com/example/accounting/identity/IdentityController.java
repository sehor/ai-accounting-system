package com.example.accounting.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class IdentityController {

    private final CurrentUserResolver currentUserResolver;
    private final IdentityService identityService;

    public IdentityController(CurrentUserResolver currentUserResolver, IdentityService identityService) {
        this.currentUserResolver = currentUserResolver;
        this.identityService = identityService;
    }

    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        return identityService.ensureUser(currentUserResolver.resolveUser(request));
    }
}
