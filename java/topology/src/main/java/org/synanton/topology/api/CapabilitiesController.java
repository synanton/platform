package org.synanton.topology.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/capabilities")
public class CapabilitiesController {

    @GetMapping
    public Map<String, Object> capabilities() {
        return Map.of(
                "module_id", "topology",
                "module_version", "0.1.0",
                "features", Map.of(
                        "FS_ACL_SEEDING", "NATIVE",
                        "MANUAL_GRANTS", "NATIVE",
                        "OUTBOX_DISPATCH", "FALLBACK",
                        "NEO4J_PROJECTION", "FALLBACK"
                )
        );
    }
}
