package com.nexus360.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * JPA slice on the real embedded PostgreSQL 17 (Flyway-migrated, schema validated by Hibernate). Each test runs in a
 * transaction that is rolled back, so repository tests leave no rows behind in the shared database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class RepositoryTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // @ConfigurationPropertiesScan on the application class also binds (and validates) AppProperties in the slice.
        registry.add("app.security.jwt-secret", () -> "repository-tests-only-secret-0123456789abcdef");
        registry.add("app.security.allowed-origins", () -> "http://localhost:5173");
    }
}
