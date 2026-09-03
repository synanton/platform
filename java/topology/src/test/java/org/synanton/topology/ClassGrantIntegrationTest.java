package org.synanton.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.synanton.topology.infra.jdbc.JdbcClassGrantMutationStore;
import org.synanton.topology.infra.jdbc.JdbcClassGrantRepository;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({JdbcClassGrantRepository.class, JdbcClassGrantMutationStore.class, ClassGrantIntegrationTest.TestConfig.class})
@Sql("/db/schema-test.sql")
class ClassGrantIntegrationTest {

    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired JdbcClassGrantRepository classGrants;
    @Autowired JdbcClassGrantMutationStore mutationStore;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM topology.class_grants");
    }

    @Test
    void shouldResolveCallerClassesWithPublicDefault() {
        assertThat(classGrants.resolveCallerClasses("demo", "user:bob", Set.of()))
                .containsExactly("PUBLIC");
    }

    @Test
    void shouldResolveGroupClassGrant() {
        mutationStore.insertPending(
                new org.synanton.topology.domain.ClassGrantMutationService.ClassGrantCommand(
                        "demo", "4000", "GROUP", "PERSONAL", "SEARCH", "k1",
                        "admin", "USER_SUBJECT", "user"),
                java.time.Instant.parse("2026-08-28T09:00:00Z")
        );

        Set<String> classes = classGrants.resolveCallerClasses("demo", "user:alice", Set.of("4000"));

        assertThat(classes).containsExactlyInAnyOrder("PUBLIC", "PERSONAL");
    }

    @Test
    void shouldListActiveClassGrantsByTenant() {
        UUID grantId = mutationStore.insertPending(
                new org.synanton.topology.domain.ClassGrantMutationService.ClassGrantCommand(
                        "demo", "payroll", "GROUP", "FINANCIAL", "SEARCH", "k2",
                        "admin", "USER_SUBJECT", "user"),
                java.time.Instant.parse("2026-08-28T09:00:00Z")
        );

        var grants = classGrants.findActiveByTenant("demo");

        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().grantId()).isEqualTo(grantId);
        assertThat(grants.getFirst().sensitivityClass()).isEqualTo("FINANCIAL");
    }
}
