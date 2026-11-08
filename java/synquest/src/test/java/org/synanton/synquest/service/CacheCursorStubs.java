package org.synanton.synquest.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.synanton.ingestioncache.client.IngestionCacheClient;

/**
 * Bridges tests that stub the legacy list reads to the streaming cursors the
 * index builder now uses. The cursor answer delegates to the already-stubbed
 * {@code listManifest}/{@code readChunks}, so the seeded corpus reaches the
 * builder unchanged. Production uses the driver-paged cursor implementations.
 */
final class CacheCursorStubs {

    private CacheCursorStubs() {
    }

    static void wire(IngestionCacheClient cache) {
        when(cache.manifestCursor(anyString(), anyInt()))
                .thenAnswer(invocation -> cache.listManifest(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(cache.chunkCursor(anyString(), any()))
                .thenAnswer(invocation -> cache.readChunks(
                        invocation.getArgument(0), invocation.getArgument(1)));
    }
}
