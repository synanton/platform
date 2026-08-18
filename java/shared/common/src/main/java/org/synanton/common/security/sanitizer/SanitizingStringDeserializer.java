package org.synanton.common.security.sanitizer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.owasp.html.PolicyFactory;
import org.synanton.common.tenant.TenantContext;

import java.io.IOException;

/**
 * OWASP HTML sanitiser applied to every JSON {@link String} field unless {@link AllowHtml}
 * is present on the target property.
 */
public class SanitizingStringDeserializer extends JsonDeserializer<String> implements ContextualDeserializer {

    private final PolicyFactory policy;
    private final MeterRegistry meterRegistry;
    private final boolean allowHtml;
    private final String fieldName;

    public SanitizingStringDeserializer(PolicyFactory policy, MeterRegistry meterRegistry) {
        this(policy, meterRegistry, false, "unknown");
    }

    SanitizingStringDeserializer(
            PolicyFactory policy,
            MeterRegistry meterRegistry,
            boolean allowHtml,
            String fieldName
    ) {
        this.policy = policy;
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
        this.allowHtml = allowHtml;
        this.fieldName = fieldName;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        boolean skip = property != null && property.getAnnotation(AllowHtml.class) != null;
        String name = property != null ? property.getName() : "unknown";
        return new SanitizingStringDeserializer(policy, meterRegistry, skip, name);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        String raw = parser.getValueAsString();
        if (raw == null) {
            return null;
        }
        String tenant = resolveTenant();
        if (allowHtml) {
            meterRegistry.counter("synapt_sanitization_skipped_total", "tenant", tenant, "field", fieldName)
                    .increment();
            return raw;
        }
        String cleaned = policy.sanitize(raw);
        if (!cleaned.equals(raw)) {
            meterRegistry.counter("synapt_sanitization_applied_total", "tenant", tenant, "field", fieldName)
                    .increment();
        }
        return cleaned;
    }

    private static String resolveTenant() {
        TenantContext ctx = TenantContext.get();
        return ctx != null && ctx.tenantId() != null ? ctx.tenantId() : "unknown";
    }
}
