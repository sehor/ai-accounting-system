package com.example.accounting.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ExperienceResponses {

    private ExperienceResponses() {
    }

    public record Experience(UUID id, ExperienceScope scope, UUID ledgerId, String title, String content,
                             List<String> tags, String status, long version, UUID createdBy, UUID updatedBy,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    @Schema(name = "ExperiencePage")
    public record Page(List<Experience> items, int page, int pageSize, long totalItems, int totalPages) {
    }
}
