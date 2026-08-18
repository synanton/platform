package org.synanton.synapt.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.common.tenant.TenantContext;

import java.util.Map;

@RestController
@RequestMapping("/admin/_internal")
public class InternalAdminController {

    @GetMapping("/status")
    public Map<String, Object> status() {
        requireSupportAdmin();
        return Map.of("status", "ok");
    }

    @PostMapping("/clean")
    public ResponseEntity<Map<String, String>> clean(@RequestBody Map<String, String> body) {
        requireSupportAdmin();
        return ResponseEntity.ok(Map.of("status", "cleaned", "target", body.getOrDefault("target", "cache")));
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, String>> delete(@RequestBody Map<String, String> body) {
        requireSupportAdmin();
        if (!"I_AM_SURE".equals(body.get("confirm"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "confirm must be I_AM_SURE"));
        }
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private static void requireSupportAdmin() {
        TenantContext ctx = TenantContext.get();
        if (ctx == null || !ctx.hasRole("support_admin")) {
            throw new org.synanton.common.error.ForbiddenException("support_admin required");
        }
    }
}
