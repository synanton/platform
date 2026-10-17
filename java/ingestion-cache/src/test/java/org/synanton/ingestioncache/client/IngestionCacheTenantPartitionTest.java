package org.synanton.ingestioncache.client;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionCacheTenantPartitionTest {

    @Test
    void sourceLookupBindsOnlyTheAuthenticatedTenantPartition() {
        CqlSession session = mock(CqlSession.class);
        ResultSet result = mock(ResultSet.class);
        when(session.execute(any(SimpleStatement.class))).thenReturn(result);
        when(result.one()).thenReturn(null);
        IngestionCacheClient client = new IngestionCacheClient(session);

        assertThat(client.readSource("tenant-a", "shared-source")).isEmpty();

        ArgumentCaptor<SimpleStatement> statement = ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session).execute(statement.capture());
        assertThat(statement.getValue().getQuery())
            .contains("WHERE tenant_id=? AND source_id=?")
            .doesNotContain("ALLOW FILTERING");
        assertThat(statement.getValue().getPositionalValues()).containsExactly("tenant-a", "shared-source");
    }
}
