package com.example.accounting.agent;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class ExperienceRequests {

    private ExperienceRequests() {
    }

    public record Create(ExperienceScope scope, @Nullable UUID ledgerId, String title, String content,
                         List<String> tags) {
    }

    public record Search(@Nullable UUID ledgerId, String query, List<String> tags, Integer page, Integer pageSize) {
    }

    public record Update(long expectedVersion, String title, String content, List<String> tags) {
    }
}
