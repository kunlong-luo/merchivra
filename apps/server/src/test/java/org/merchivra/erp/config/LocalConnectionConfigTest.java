package org.merchivra.erp.config;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalConnectionConfigTest {
    private PropertySourcesPropertyResolver resolver(Map<String, Object> overrides) throws IOException {
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("syntheticOverrides", overrides));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        return new PropertySourcesPropertyResolver(sources);
    }

    @Test
    void fullJdbcUrlOverridesSplitInputs() throws IOException {
        var config = resolver(Map.of("DB_URL", "jdbc:postgresql://127.0.0.1:5544/isolated",
                "DB_HOST", "unused", "DB_PORT", "1234", "DB_NAME", "unused",
                "DB_PASSWORD", "synthetic-only", "SERVER_PORT", "8081"));
        assertEquals("jdbc:postgresql://127.0.0.1:5544/isolated", config.getProperty("spring.datasource.url"));
        assertEquals("synthetic-only", config.getProperty("spring.datasource.password"));
        assertEquals("8081", config.getProperty("server.port"));
    }

    @Test
    void existingComposeInputsAndFailClosedDefaultsArePreserved() throws IOException {
        var config = resolver(Map.of("DB_HOST", "postgres", "DB_PORT", "5432", "DB_NAME", "merchivra"));
        assertEquals("jdbc:postgresql://postgres:5432/merchivra", config.getProperty("spring.datasource.url"));
        assertEquals("merchivra", config.getProperty("spring.datasource.username"));
        assertEquals("", config.getProperty("spring.datasource.password"));
        assertEquals("8080", config.getProperty("server.port"));
        assertEquals("true", config.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("false", config.getProperty("merchivra.spapi.enabled"));
        assertEquals("false", config.getProperty("merchivra.spapi.oauth.enabled"));
    }
}
