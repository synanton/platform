package org.synanton.synquest.postgres;

import org.junit.jupiter.api.Test;
import org.synanton.storage.provider.DeploymentRequirements;
import org.synanton.storage.provider.ProviderRegistry;
import org.synanton.storage.provider.ProviderSelection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * PG-POC-007 wiring proof (007-1: scaffold). Expected GREEN: the engine
 * reports {@code postgres} on every port it implements, and a registry with
 * it registered resolves a full-postgres selection without touching a
 * database. Startup validation still fails fast for ports with no PG
 * adapter — that discipline lives in {@code StartupValidatorTest} and is
 * unaffected (it builds its own registry).
 */
class PostgresSynquestRegistrationTest {

    private static PostgresSynquestEngine engine() {
        return new PostgresSynquestEngine(UnconnectedDataSource.instance());
    }

    @Test
    void adapterNameIsPostgres() {
        assertThat(engine().adapterName()).isEqualTo("postgres");
    }

    @Test
    void fullPostgresSelectionValidates() {
        PostgresSynquestEngine engine = engine();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register("synvault", "postgres", engine);
        registry.register("synquest", "postgres", engine);
        registry.register("writer", "postgres", engine);
        registry.register("admin", "postgres", engine);
        assertThatNoException()
                .isThrownBy(
                        () ->
                                registry.validate(
                                        new ProviderSelection(
                                                "postgres", "postgres", "postgres", "postgres"),
                                        DeploymentRequirements.metadataOnly(false)));
    }
}
