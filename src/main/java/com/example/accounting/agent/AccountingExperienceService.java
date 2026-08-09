package com.example.accounting.agent;

import java.util.UUID;

public interface AccountingExperienceService {

    ExperienceResponses.Experience create(UUID actorId, ExperienceRequests.Create request);

    ExperienceResponses.Page search(UUID actorId, ExperienceRequests.Search request);

    ExperienceResponses.Experience update(UUID actorId, UUID experienceId, ExperienceRequests.Update request);

    ExperienceResponses.Experience archive(UUID actorId, UUID experienceId, long expectedVersion);
}
