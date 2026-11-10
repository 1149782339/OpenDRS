package io.opendrs.datacheck.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.EndpointRow;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.Map;

/** Endpoint row to the neutral JDBC descriptor shared by the coordinator and the candidate reader. */
public final class EndpointConfigs {

    private EndpointConfigs() {
    }

    public static JdbcConnectionConfig toConfig(EndpointRow endpoint, ObjectMapper objectMapper) {
        return JdbcConnectionConfig.builder()
                .type(endpoint.type())
                .host(endpoint.host())
                .port(endpoint.port())
                .database(endpoint.database())
                .username(endpoint.username())
                .password(endpoint.password())
                .extras(extras(endpoint.optionsJson(), objectMapper))
                .build();
    }

    private static Map<String, Object> extras(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
