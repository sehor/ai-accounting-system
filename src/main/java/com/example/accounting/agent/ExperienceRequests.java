package com.example.accounting.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public final class ExperienceRequests {

    private ExperienceRequests() {
    }

    @Schema(name = "ExperienceCreateRequest")
    public record Create(ExperienceScope scope, UUID ledgerId, String title, String content, List<String> tags) {
    }

    @Schema(name = "ExperienceSearchRequest")
    public record Search(UUID ledgerId, String query, List<String> tags, Integer page, Integer pageSize) {
    }

    @Schema(name = "ExperienceUpdateRequest")
    public record Update(long expectedVersion, String title, String content, List<String> tags) {
    }
}
