package org.synanton.synquest.demo;

import org.synanton.common.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Demo-only graph neighborhood endpoint. Not a production graph contract. */
@RestController
public class DemoGraphController {

    private final DemoGraphService service;

    public DemoGraphController(DemoGraphService service) {
        this.service = service;
    }

    @GetMapping("/v1/demo/graph/neighbors/{chunkId}")
    public ResponseEntity<DemoGraphService.GraphResponse> neighbors(@PathVariable String chunkId) throws IOException {
        if (TenantContext.get() == null) {
            throw new IllegalStateException("Verified tenant context is required");
        }
        DemoGraphService.GraphResponse response = service.neighbors(chunkId);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }
}
