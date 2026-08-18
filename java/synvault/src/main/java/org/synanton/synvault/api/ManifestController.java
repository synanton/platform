package org.synanton.synvault.api;

import org.synanton.common.tenant.TenantContext;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synvault.port.ObjectStorePort;
import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.synanton.synvault.security.TenantScopeGuard;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class ManifestController {

    private final IngestionCacheClient cacheClient;
    private final ObjectStorePort objectStore;
    private final SynvaultObjectStoreProperties storeProps;

    private final TenantScopeGuard tenantScopeGuard = new TenantScopeGuard();

    public ManifestController(IngestionCacheClient cacheClient, ObjectStorePort objectStore, SynvaultObjectStoreProperties storeProps) {
        this.cacheClient = cacheClient;
        this.objectStore = objectStore;
        this.storeProps = storeProps;
    }

    @GetMapping("/manifest/{tenant}")
    public List<ManifestRow> listManifest(
            @PathVariable String tenant,
            @RequestParam(defaultValue = "100") int limit) {
        tenantScopeGuard.check(tenant);
        return cacheClient.listManifest(tenant, limit);
    }

    @GetMapping("/manifest/{tenant}/{ref}")
    public ResponseEntity<ManifestRow> getManifest(@PathVariable String tenant, @PathVariable UUID ref) {
        tenantScopeGuard.check(tenant);
        return cacheClient.readManifest(tenant, ref)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/content/{tenant}/{ref}")
    public ResponseEntity<Void> getContent(@PathVariable String tenant, @PathVariable UUID ref) {
        tenantScopeGuard.check(tenant);
        var manifest = cacheClient.readManifest(tenant, ref).orElse(null);
        if (manifest == null) return ResponseEntity.notFound().build();
        String key = tenant + "/" + ref;
        String url = objectStore.presignedUrl(storeProps.hotBucket(), key, 15);
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }
}
