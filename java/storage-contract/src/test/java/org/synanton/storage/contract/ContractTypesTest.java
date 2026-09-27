package org.synanton.storage.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTypesTest {

    private static final TenantScope TENANT = TenantScope.of("tenant_07");
    private static final PolicyContext POLICY = PolicyContext.of("policy-main", "rev-12");

    @Test
    void tenantScopeRejectsBlank() {
        assertThatThrownBy(() -> new TenantScope("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void securityContextRequiresPrincipals() {
        assertThatThrownBy(() -> new SecurityContext(TENANT, List.of(), POLICY, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void securityContextNeverBroadens() {
        SecurityContext ctx = SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
        assertThat(ctx.narrowTo(TENANT)).isSameAs(ctx);
        assertThatThrownBy(() -> ctx.narrowTo(TenantScope.of("tenant_11")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void serviceContextRequiresServicePrincipal() {
        assertThatThrownBy(
                        () -> SecurityContext.service(TENANT, PrincipalRef.user("u-1"), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        SecurityContext svc = SecurityContext.service(TENANT, PrincipalRef.service("relay"), POLICY);
        assertThat(svc.service()).isTrue();
    }

    @Test
    void idsRejectBlank() {
        assertThatThrownBy(() -> DocumentId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GenerationId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceVersionId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionSeriesId.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddingModelRefRequiresAllParts() {
        assertThatThrownBy(() -> EmbeddingModelRef.of("m", "v", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageExceptionCarriesKindAndRetryability() {
        StorageException transientFailure = new StorageException(StorageErrorKind.TRANSIENT, "boom");
        assertThat(transientFailure.retryable()).isTrue();
        assertThat(StorageException.notFound("missing").retryable()).isFalse();
        assertThat(StorageException.conflict("rev").kind()).isEqualTo(StorageErrorKind.CONFLICT);
    }

    @Test
    void pageRequestBoundsLimit() {
        assertThatThrownBy(() -> PageRequest.first(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRequest.first(1001)).isInstanceOf(IllegalArgumentException.class);
        PageRequest first = PageRequest.first(50);
        assertThat(first.cursor()).isEqualTo(Optional.empty());
        assertThat(PageRequest.after(50, "c").cursor()).contains("c");
    }
}
