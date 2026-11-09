package org.synanton.relix.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;

class RelixCanonicalRelationPromoterTest {

    private static final String TENANT = "tenant-promoter";
    private static final UUID VERSION = UUID.randomUUID();
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final GraphConnector connector = mock(GraphConnector.class);
    private final RelixCanonicalRelationPromoter promoter =
            new RelixCanonicalRelationPromoter(cache, connector);

    @Test
    void validRowDelegatesAndInvalidRowFailsClosed() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(
                row("VALID"), row("INVALID")));
        when(connector.promoteRelation(any())).thenReturn(new PromotionResult(1, 1, 1));

        assertThat(promoter.promoteRelation(relation())).isEqualTo(new PromotionResult(1, 1, 1));
        verify(connector).promoteRelation(relation());
    }

    @Test
    void invalidValidationRowNeverCallsTheGraph() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of(row("INVALID")));
        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(IllegalStateException.class);
        verify(connector, never()).promoteRelation(any());
    }

    @Test
    void missingValidationRowNeverCallsTheGraph() {
        when(cache.listRelationPromotionResults(TENANT, VERSION)).thenReturn(List.of());
        assertThatThrownBy(() -> promoter.promoteRelation(relation()))
                .isInstanceOf(IllegalStateException.class);
        verify(connector, never()).promoteRelation(any());
    }

    private static CanonicalRelation relation() {
        return new CanonicalRelation(TENANT, VERSION.toString(), "r1", "m1", "e1",
                "m2", "e2", "requires", "1.0.0", "ref:0", 0, 20);
    }

    private static RelationPromotionResultRow row(String status) {
        return new RelationPromotionResultRow(TENANT, VERSION, "r1", "m1", "m2", "requires",
                status, "e1", "e2", status, "{}", "relation-validation-v1", "1.0.0",
                Instant.now(), null);
    }
}
