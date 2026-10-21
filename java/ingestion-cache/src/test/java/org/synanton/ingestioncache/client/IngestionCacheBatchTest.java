package org.synanton.ingestioncache.client;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.domain.ChunkRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionCacheBatchTest {

    @Test
    void largeDocumentsAreSplitIntoBoundedBatches() {
        CqlSession session = mock(CqlSession.class);
        ResultSet result = mock(ResultSet.class);
        when(session.execute(any(SimpleStatement.class))).thenReturn(result);
        when(session.execute(any(BatchStatement.class))).thenReturn(result);

        UUID contentRefId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID processingRunId = UUID.randomUUID();
        List<ChunkRow> chunks = new ArrayList<>();
        for (int ordinal = 0; ordinal < 25; ordinal++) {
            chunks.add(new ChunkRow(
                    "tenant-a", contentRefId, ordinal, "chunk " + ordinal, "aa".repeat(32),
                    -1, -1, "", "", "", "[]", 0, "", false, ChunkRow.PUBLIC_ONLY,
                    "", "", 0, Set.of(sourceVersionId), processingRunId));
        }

        new IngestionCacheClient(session).recordKnowledgeChunks(
                "tenant-a", contentRefId, sourceVersionId, processingRunId, chunks);

        // 25 chunks at 10 per batch = 3 batches; no single whole-document batch.
        verify(session, times(3)).execute(any(BatchStatement.class));
    }
}
