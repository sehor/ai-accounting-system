package com.example.accounting.fixedasset.internal.application;

import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AuditSnapshotSerializer {

    private final ObjectMapper objectMapper;

    public AuditSnapshotSerializer() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public AuditSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiProblemException(500, "FIXED_ASSET_AUDIT_SNAPSHOT_FAILED",
                    "Fixed-asset audit snapshot failed",
                    "The fixed-asset change could not be serialized", false);
        }
    }
}
