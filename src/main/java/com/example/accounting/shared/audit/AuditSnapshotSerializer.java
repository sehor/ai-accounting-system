package com.example.accounting.shared.audit;

import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Fail-closed JSON serializer shared by audited business workflows. */
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
        return serialize(value, "AUDIT_SNAPSHOT_FAILED", "Audit snapshot failed",
                "The business change could not be serialized for audit");
    }

    public String serialize(Object value, String code, String title, String detail) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiProblemException(500, code, title, detail, false);
        }
    }
}
