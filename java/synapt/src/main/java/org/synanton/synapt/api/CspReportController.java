package org.synanton.synapt.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CspReportController {

    private final MeterRegistry meterRegistry;

    public CspReportController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
    }

    @PostMapping("/csp-report")
    public ResponseEntity<Void> report(@RequestBody Map<String, Object> body) {
        Object report = body.getOrDefault("csp-report", body);
        String directive = "unknown";
        String blocked = "unknown";
        if (report instanceof Map<?, ?> map) {
            Object directiveValue = map.get("violated-directive");
            Object blockedValue = map.get("blocked-uri");
            directive = directiveValue == null ? "unknown" : String.valueOf(directiveValue);
            blocked = blockedValue == null ? "unknown" : String.valueOf(blockedValue);
        }
        meterRegistry.counter("ui_csp_violation_report_total", "directive", directive, "blocked_uri", blocked)
                .increment();
        return ResponseEntity.noContent().build();
    }
}
