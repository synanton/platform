package org.synanton.synquest.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceOutboxRow;

class SearchProjectionConsumerTest {

    private static SourceOutboxRow published(String tenant) {
        return new SourceOutboxRow(
                tenant, "upload", "source", "resource", UUID.randomUUID(),
                "SourcePublished", UUID.randomUUID(), null, "{}", Instant.now(),
                false, null, 0, null, null, false);
    }

    @Test
    void pendingEventsRebuildProjectionAndMarkPublished() throws Exception {
        var cache = mock(IngestionCacheClient.class);
        var search = mock(SearchService.class);
        SourceOutboxRow event = published("demo");
        when(cache.listUnpublishedSourceOutbox(eq("demo"), anyInt(), any(Instant.class)))
                .thenReturn(List.of(event));

        new SearchProjectionConsumer(cache, search, "demo", 200).projectNewSourceVersions();

        verify(search).reindex("demo");
        verify(cache).markSourceOutboxPublished(eq(event), any(Instant.class));
    }

    @Test
    void noPendingEventsDoesNotRebuild() throws Exception {
        var cache = mock(IngestionCacheClient.class);
        var search = mock(SearchService.class);
        when(cache.listUnpublishedSourceOutbox(eq("demo"), anyInt(), any(Instant.class)))
                .thenReturn(List.of());

        new SearchProjectionConsumer(cache, search, "demo", 200).projectNewSourceVersions();

        verify(search, never()).reindex(anyString());
    }

    @Test
    void blankTenantIsNoOp() throws Exception {
        var cache = mock(IngestionCacheClient.class);
        var search = mock(SearchService.class);

        new SearchProjectionConsumer(cache, search, "", 200).projectNewSourceVersions();

        verify(search, never()).reindex(anyString());
        verify(cache, never()).listUnpublishedSourceOutbox(anyString(), anyInt(), any(Instant.class));
    }
}
