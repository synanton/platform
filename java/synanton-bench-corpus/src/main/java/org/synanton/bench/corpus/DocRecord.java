package org.synanton.bench.corpus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 028a.3 document generation (spec §1 exact counts, §3 Zipf).
 *
 * <p>Ids are derived, not random: {@code UUID.nameUUIDFromBytes("v1-doc-&lt;d&gt;")}
 * is stable across regenerations, JVMs, and languages (v3/md5 or v5/sha1 —
 * this generator uses {@code nameUUIDFromBytes}, i.e. v3 MD5; ports must use
 * the same function, recorded here precisely for that reason).
 */
public record DocRecord(int docIndex, UUID docId, int tenantIndex, String tenantId) {

    /** Total documents in v1 (exact, not average). */
    public static final int DOCS = 20_000;

    /** Chunks per document: exactly 8 (20,000 × 8 = 160,000 — exact counts both ends). */
    public static final int CHUNKS_PER_DOC = 8;

    public static UUID docId(int docIndex) {
        return UUID.nameUUIDFromBytes(("v1-doc-" + docIndex).getBytes(StandardCharsets.UTF_8));
    }

    public static List<DocRecord> generate(TenantDealing dealing) {
        List<DocRecord> docs = new ArrayList<>(DOCS);
        for (int d = 0; d < DOCS; d++) {
            int tenant = dealing.tenant(d);
            docs.add(new DocRecord(d, docId(d), tenant, TenantDealing.tenantId(tenant)));
        }
        return List.copyOf(docs);
    }
}
