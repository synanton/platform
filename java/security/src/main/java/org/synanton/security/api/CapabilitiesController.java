package org.synanton.security.api;

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
                "module_id", "security",
                "module_version", "0.1.0",
                "features", Map.of(
                        "JWT_ISSUANCE", "NATIVE",
                        "HTPASSWD_BACKEND", "NATIVE",
                        "PAM_BACKEND", "EMULATED",
                        "OUTBOUND_AUTH_BROKER", "FALLBACK"
                )
        );
    }
}
