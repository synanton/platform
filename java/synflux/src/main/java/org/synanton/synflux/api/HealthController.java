package org.synanton.synflux.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness/readiness for the ingestion plane — anonymous, no tenant data.
 * k8s probes cannot authenticate (a JWT-guarded probe would 401 and pin the
 * pod NotReady); business readiness is per-tenant and lives elsewhere.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
