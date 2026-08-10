package com.example.accounting.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives every Spring integration-test JVM a disposable PostgreSQL schema.
 *
 * <p>The customizer runs before the application context is refreshed, so the
 * schema exists before Flyway starts and every application connection points
 * at the isolated schema instead of {@code public}.</p>
 */
public final class IsolatedDatabaseContextCustomizerFactory implements ContextCustomizerFactory {

    private static final ContextCustomizer CUSTOMIZER = new IsolatedDatabaseContextCustomizer();

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return AnnotatedElementUtils.hasAnnotation(testClass, SpringBootTest.class)
                ? CUSTOMIZER
                : null;
    }

    private static final class IsolatedDatabaseContextCustomizer implements ContextCustomizer {

        private static final AtomicReference<DatabaseSchema> SCHEMA = new AtomicReference<>();

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + testDatabaseUrl(context.getEnvironment()))
                    .applyTo(context.getEnvironment());
            DatabaseSchema schema = ensureSchema(context.getEnvironment());
            TestPropertyValues.of(
                    "spring.datasource.hikari.schema=" + schema.name(),
                    "spring.flyway.schemas=" + schema.name(),
                    "spring.flyway.default-schema=" + schema.name(),
                    "spring.flyway.create-schemas=false")
                    .applyTo(context.getEnvironment());
        }

        private static String testDatabaseUrl(ConfigurableEnvironment environment) {
            return environment.getProperty(
                    "TEST_DB_URL", "jdbc:postgresql://localhost:5432/ai-accounting-test");
        }

        private static DatabaseSchema ensureSchema(ConfigurableEnvironment environment) {
            DatabaseSchema existing = SCHEMA.get();
            if (existing != null) {
                return existing;
            }

            synchronized (SCHEMA) {
                existing = SCHEMA.get();
                if (existing != null) {
                    return existing;
                }

                DatabaseSchema created = createSchema(environment);
                SCHEMA.set(created);
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> dropSchema(created), "drop-isolated-test-schema"));
                return created;
            }
        }

        private static DatabaseSchema createSchema(ConfigurableEnvironment environment) {
            String url = required(environment, "spring.datasource.url");
            String username = required(environment, "spring.datasource.username");
            String password = environment.getProperty("spring.datasource.password", "");
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new IllegalStateException(
                        "Spring integration tests require a PostgreSQL datasource for schema isolation");
            }

            String name = "test_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("create schema " + quoteIdentifier(name));
                return new DatabaseSchema(name, url, username, password);
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to create isolated test schema", exception);
            }
        }

        private static void dropSchema(DatabaseSchema schema) {
            try (Connection connection = DriverManager.getConnection(
                    schema.url(), schema.username(), schema.password());
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + quoteIdentifier(schema.name()) + " cascade");
            } catch (SQLException exception) {
                System.err.println("Unable to drop isolated test schema " + schema.name());
            }
        }

        private static String required(ConfigurableEnvironment environment, String key) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing datasource property: " + key);
            }
            return value;
        }

        private static String quoteIdentifier(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }
    }

    private record DatabaseSchema(String name, String url, String username, String password) {
    }
}
