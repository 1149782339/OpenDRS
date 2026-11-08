package io.opendrs.migration.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access to {@code /api/**}, disabled unless explicitly configured.
 *
 * <p>The reference deployment puts the SPA and the API behind one origin (nginx serves the
 * static build and reverse proxies {@code /api}), so no browser request is ever cross-origin
 * and this configuration stays inert. Only set {@code OPENDRS_CORS_ALLOWED_ORIGINS} when the
 * front end is served from a different origin than the API, for example:
 *
 * <pre>
 * OPENDRS_CORS_ALLOWED_ORIGINS=http://localhost:8000,https://opendrs.example.com
 * </pre>
 *
 * <p>An empty value registers no CORS rules at all, which is safer than allowing {@code *}:
 * this API creates and stops migration jobs against arbitrary databases.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${opendrs.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
