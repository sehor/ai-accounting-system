package com.example.accounting.ledger.formula;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** Serializes and deserializes {@link ReportFormulaDefinition} to/from its JSON form. */
@Component
public class FormulaParser {

    private final ObjectMapper objectMapper;

    public FormulaParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Convenience constructor for focused unit tests. */
    public FormulaParser() {
        this(new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public ReportFormulaDefinition parse(String json) {
        try {
            return objectMapper.readValue(json, ReportFormulaDefinition.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid report formula definition JSON", exception);
        }
    }

    /** Parses a legacy (pre-schema) formula JSON document without mapping it to the schema. */
    public com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid report formula JSON", exception);
        }
    }

    public String write(ReportFormulaDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize report formula definition", exception);
        }
    }
}
