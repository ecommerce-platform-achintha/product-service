package com.achintha.productservice.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A string map as a JSON object in a text column (variant attributes, e.g. {"Size":"M"}); keeps the order. */
@Converter
public class StringMapConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> values) {
        return JSON.writeValueAsString(values == null ? Map.of() : values);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? Map.of()
                : Collections.unmodifiableMap(JSON.readValue(json, TYPE));
    }
}
