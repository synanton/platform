package org.synanton.synflux;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.synanton.common.tenant.TenantContextFilter;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural assertion over the composed boot's filter wiring — the class of
 * bug that sliced tests cannot see.
 *
 * <p>M70q run 1 exposed it: {@code SynfluxApplication} component-scanned the
 * whole {@code org.synanton.synvault} tree and picked up the legacy
 * {@code synvault.filter.MockTenantFilter}, which reads the inbound X-Tenant
 * header and overwrites {@code TenantContext} with {@code Anonymous("demo")}.
 * The correct JWT {@code TenantContextFilter} runs at HIGHEST_PRECEDENCE,
 * the mock auto-registers at the default LOWEST — so the mock ran last and
 * every verified request reached controllers as tenant "demo", subject null.
 * Sliced tests ({@code MockMvcBuilders.standaloneSetup}) never scan sibling
 * packages, so only a full-context boot can catch it.
 *
 * <p>This test asserts the wiring, not behavior: no MockTenantFilter bean is
 * registered, the verified-context filter is, and no other auto-registered
 * tenant filter can overwrite the context.
 */
@SpringBootTest(properties = {
        "SYNANTON_JWT_SECRET=filter-registration-test-secret-32bytes!!",
        "SYNFLUX_FIXTURE_ROOT=/tmp/synflux-filter-test-fixtures",
        "SYNFLUX_CONTENT_CACHE_BACKEND=filesystem",
        "SYNFLUX_CONTENT_CACHE_ROOT=/tmp/synflux-filter-test-cc",
        "EXTRACTION_CLIENT_ENABLED=false",
})
class TenantFilterRegistrationTest {

    static final String FIXTURE_ROOT = "/tmp/synflux-filter-test-fixtures";
    static final String CONTENT_CACHE_ROOT = "/tmp/synflux-filter-test-cc";

    static final CassandraContainer CASSANDRA =
            new CassandraContainer(DockerImageName.parse("cassandra:4.1"));

    static {
        // Static init runs before the Spring test context loads: the boot's
        // FilesystemFixturePolicy requires both roots to exist.
        try {
            Files.createDirectories(Path.of(FIXTURE_ROOT));
            Files.createDirectories(Path.of(CONTENT_CACHE_ROOT));
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare test directories", e);
        }
        CASSANDRA.start();
        System.setProperty("CASSANDRA_CONTACT_POINTS", CASSANDRA.getHost());
        System.setProperty("CASSANDRA_PORT", String.valueOf(CASSANDRA.getMappedPort(9042)));
        System.setProperty("CASSANDRA_LOCAL_DC", "datacenter1");
    }

    @Autowired
    ApplicationContext context;

    @Test
    void onlyTheVerifiedContextFilterIsRegistered() {
        List<String> registeredFilters = context.getBeansOfType(FilterRegistrationBean.class).values()
                .stream()
                .map(registration -> registration.getFilter().getClass().getSimpleName())
                .toList();

        assertThat(registeredFilters)
                .as("the JWT tenant filter must be registered")
                .contains(TenantContextFilter.class.getSimpleName());

        // The next legacy filter will have a different class name — guard the
        // FAMILY by naming convention, not one class: anything that smells of
        // demo/mock/anonymous tenancy must never be registered in this boot.
        assertThat(registeredFilters)
                .as("no demo/mock/anonymous tenant filter may be registered")
                .noneMatch(name -> name.contains("Mock") || name.contains("Demo")
                        || name.contains("Anonymous"));

        assertThat(context.getBeanNamesForType(
                org.synanton.synvault.filter.MockTenantFilter.class))
                .as("narrowed scan: synvault.filter is not a bean source for synflux")
                .isEmpty();
    }
}
