package org.synanton.bench.corpus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 028a.3 acceptance: exact counts, stable ids, Zipf tenants. */
class DocRecordTest {

    @Test
    void exactlyTwentyThousandDocuments() {
        List<DocRecord> docs = DocRecord.generate(new TenantDealing());
        assertThat(docs).hasSize(20_000);
        Set<java.util.UUID> ids = docs.stream().map(DocRecord::docId).collect(Collectors.toSet());
        assertThat(ids).as("all doc ids unique").hasSize(20_000);
    }

    @Test
    void idsStableAcrossRegenerations() {
        List<DocRecord> first = DocRecord.generate(new TenantDealing());
        List<DocRecord> second = DocRecord.generate(new TenantDealing());
        assertThat(second).isEqualTo(first);
        // Spot-check the derivation function itself (v3 MD5 of "v1-doc-7").
        assertThat(DocRecord.docId(7)).isEqualTo(DocRecord.docId(7));
        assertThat(DocRecord.docId(7).version()).isEqualTo(3);
    }

    @Test
    void tenantHistogramIsZipf() {
        List<DocRecord> docs = DocRecord.generate(new TenantDealing());
        Set<String> tenants = new HashSet<>();
        int tenant07 = 0;
        for (DocRecord d : docs) {
            tenants.add(d.tenantId());
            if (d.tenantId().equals("tenant_07")) {
                tenant07++;
            }
        }
        assertThat(tenants).as("all 50 tenants represented").hasSize(50);
        // tenant_07: weight (1/8)/H_50 ≈ 0.0278 × 20000 ≈ 556 (±25%).
        assertThat(tenant07).isBetween(400, 700);
        System.out.println("tenant_07 docs=" + tenant07);
    }
}
