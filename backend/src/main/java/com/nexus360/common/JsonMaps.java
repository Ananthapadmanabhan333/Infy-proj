package com.nexus360.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts maps destined for JSONB columns into the plain types Jackson reads back (strings for enums and instants,
 * Integer for small numbers). Without this Hibernate's JSON dirty check sees a difference after the round trip and
 * issues spurious UPDATEs.
 */
public final class JsonMaps {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private JsonMaps() {}

    public static Map<String, Object> normalize(Map<String, ?> source) {
        if (source == null) {
            return null;
        }
        try {
            // Round-trip through JSON text: that is exactly what Hibernate compares against on dirty checking.
            return MAPPER.readValue(MAPPER.writeValueAsString(source), MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Value cannot be stored as JSON", e);
        }
    }
}
