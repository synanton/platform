package org.synanton.synapt.filter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in gate must work in BOTH directions — a gate that never opens is
 * as broken as one that never closes:
 *
 * <ul>
 *   <li>default (no property, or false): no filter bean — no demo tenant can
 *       ever be materialized from absence;</li>
 *   <li>explicit {@code synanton.mock-tenant.enabled=true}: the filter bean
 *       exists — local/demo tooling that genuinely needs header-based
 *       tenancy keeps a supported path (otherwise someone re-introduces the
 *       hardcoded demo fallback by another route).</li>
 * </ul>
 *
 * <p>There is no default value for the property in any application.yml or
 * deployment configmap — enabling it is always an explicit operator action
 * (#306 tracks keeping it that way).
 */
class MockTenantFilterGateTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockTenantFilter.class);

    @Test
    void filterIsAbsentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MockTenantFilter.class));
    }

    @Test
    void filterIsAbsentWhenExplicitlyDisabled() {
        runner.withPropertyValues("synanton.mock-tenant.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MockTenantFilter.class));
    }

    @Test
    void filterIsPresentOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues("synanton.mock-tenant.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MockTenantFilter.class));
    }
}
