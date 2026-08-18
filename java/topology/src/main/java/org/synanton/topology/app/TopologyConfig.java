package org.synanton.topology.app;

import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.AckTracker;
import org.synanton.topology.domain.GrantMutationService;
import org.synanton.topology.domain.ResidencyPolicyValidator;
import org.synanton.topology.infra.jdbc.JdbcGrantMutationStore;
import org.synanton.topology.infra.jdbc.JdbcOrganizationPolicyRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.LinkedHashSet;

@Configuration
@EnableConfigurationProperties(TopologyProperties.class)
public class TopologyConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PgvRuleCatalogue pgvRuleCatalogue() {
        return new PgvRuleCatalogue();
    }

    @Bean
    AckTracker ackTracker() {
        return new AckTracker();
    }

    @Bean
    ResidencyPolicyValidator residencyPolicyValidator(TopologyProperties properties) {
        return new ResidencyPolicyValidator(new LinkedHashSet<>(properties.regions().registered()));
    }

    @Bean
    GrantMutationService grantMutationService(
            PgvRuleCatalogue catalogue,
            JdbcGrantMutationStore store,
            JdbcOrganizationPolicyRepository policies,
            Clock clock
    ) {
        return new GrantMutationService(catalogue, store, store, store, policies::require, clock);
    }
}
