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
    void localProfileUsesSharedLoopbackAndNonConflictingPort() throws IOException {
        var sources = new MutablePropertySources();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("local", new ClassPathResource("application-local.yml"))) {
            sources.addLast(source);
        }
        for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        var config = new PropertySourcesPropertyResolver(sources);
        assertEquals("jdbc:postgresql://127.0.0.1:5432/merchivra", config.getProperty("spring.datasource.url"));
        assertEquals("merchivra", config.getProperty("spring.datasource.username"));
        assertEquals("merchivra", config.getProperty("spring.datasource.password"));
        assertEquals("127.0.0.1", config.getProperty("spring.data.redis.host"));
        assertEquals("6379", config.getProperty("spring.data.redis.port"));
        assertEquals("redis123456", config.getProperty("spring.data.redis.password"));
        assertEquals("127.0.0.1", config.getProperty("server.address"));
        assertEquals("8081", config.getProperty("server.port"));
        assertEquals("false", config.getProperty("merchivra.redis.sessions-enabled"));
        assertEquals("false", config.getProperty("merchivra.spapi.rate-limit.enabled"));
        assertEquals("false", config.getProperty("merchivra.spapi.oauth.enabled"));
    }

    @Test
    void baseConfigurationProvidesRequestedPublicTestDefaults() throws IOException {
        var config = resolver(Map.of());
        assertEquals("merchivra", config.getProperty("spring.datasource.password"));
        assertEquals("redis123456", config.getProperty("spring.data.redis.password"));
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
    void conciseNamesResolveWithoutLegacyPlaceholderAliases() throws IOException {
        var config = resolver(Map.of(
                "REDIS_HOST", "shared-redis",
                "BOOTSTRAP_USERNAME", "local-admin",
                "EVIDENCE_AV_REQUIRED", "true",
                "EVIDENCE_AV_HOST", "clamd.internal",
                "EVIDENCE_AV_PORT", "3311",
                "SPAPI_OAUTH_ENABLED", "true",
                "OPS_SCAN_INTERVAL_MS", "12345"));
        assertEquals("shared-redis", config.getProperty("spring.data.redis.host"));
        assertEquals("local-admin", config.getProperty("merchivra.bootstrap.username"));
        assertEquals("true", config.getProperty("merchivra.evidence.av-required"));
        assertEquals("clamd.internal", config.getProperty("merchivra.evidence.av-host"));
        assertEquals("3311", config.getProperty("merchivra.evidence.av-port"));
        assertEquals("true", config.getProperty("merchivra.spapi.oauth.enabled"));
        assertEquals("12345", config.getProperty("merchivra.operations.scan-interval-ms"));
    }

    @Test
    void yamlContainsNoLegacyEnvironmentPlaceholders() throws IOException {
        for (String resource : new String[]{"application.yml", "application-local.yml"}) {
            try (var in = new ClassPathResource(resource).getInputStream()) {
                String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                org.junit.jupiter.api.Assertions.assertFalse(yaml.contains("MERCHIVRA_"));
            }
        }
    }

    @Test
    void existingComposeInputsAndFailClosedFeatureDefaultsArePreserved() throws IOException {
        var config = resolver(Map.of("DB_HOST", "postgres", "DB_PORT", "5432", "DB_NAME", "merchivra",
                "DB_PASSWORD", "synthetic-compose-password", "REDIS_PASSWORD", ""));
        assertEquals("jdbc:postgresql://postgres:5432/merchivra", config.getProperty("spring.datasource.url"));
        assertEquals("merchivra", config.getProperty("spring.datasource.username"));
        assertEquals("synthetic-compose-password", config.getProperty("spring.datasource.password"));
        assertEquals("", config.getProperty("spring.data.redis.password"));
        assertEquals("8080", config.getProperty("server.port"));
        assertEquals("true", config.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("false", config.getProperty("merchivra.spapi.enabled"));
        assertEquals("false", config.getProperty("merchivra.spapi.oauth.enabled"));
    }
}
