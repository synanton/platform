package org.synanton.common.security.sanitizer;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.List;

/**
 * Builds the platform OWASP HTML sanitiser policy from {@link SanitizerProperties}.
 */
public class HtmlSanitizerPolicyFactory {

    private final SanitizerProperties properties;
    private final PolicyFactory policy;

    public HtmlSanitizerPolicyFactory(SanitizerProperties properties) {
        this.properties = properties;
        this.policy = build(properties);
    }

    public PolicyFactory policy() {
        return policy;
    }

    public SanitizerProperties properties() {
        return properties;
    }

    public static PolicyFactory build(SanitizerProperties properties) {
        HtmlPolicyBuilder builder = new HtmlPolicyBuilder();
        List<String> tags = properties.allowedTags();
        if (tags != null && !tags.isEmpty()) {
            builder.allowElements(tags.toArray(String[]::new));
        }
        List<String> attributes = properties.allowedAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            builder.allowAttributes(attributes.toArray(String[]::new)).globally();
        }
        if (properties.allowRelativeLinks()) {
            builder.allowStandardUrlProtocols();
            builder.allowUrlProtocols("http", "https", "mailto");
        } else {
            builder.allowUrlProtocols("https", "mailto");
        }
        if (!properties.stripUnsafeCss()) {
            builder.allowStyling();
        }
        return builder.toFactory();
    }
}
