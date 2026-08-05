package com.example.accounting.agent.internal.port;

import com.example.accounting.agent.ExperienceScope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingExperienceRepository {

    Record create(ExperienceScope scope, UUID ledgerId, String title, String content, List<String> tags,
                  UUID actorId);

    Page search(UUID ledgerId, String query, List<String> tags, int limit, int offset);

    Optional<Record> find(UUID experienceId);

    boolean update(UUID experienceId, long expectedVersion, String title, String content, List<String> tags,
                   UUID actorId);

    boolean archive(UUID experienceId, long expectedVersion, UUID actorId);

    record Page(List<Record> items, long totalItems) {
    }

    record Record(UUID id, ExperienceScope scope, UUID ledgerId, String title, String content, List<String> tags,
                  String status, long version, UUID createdBy, UUID updatedBy,
                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
