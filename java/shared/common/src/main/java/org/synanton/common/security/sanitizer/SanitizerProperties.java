package org.synanton.common.security.sanitizer;

import java.util.List;

/**
 * Platform-wide HTML sanitiser settings. Bound from {@code synanton.sanitizer} when
 * a Spring Boot service installs {@link SanitizerAutoConfiguration}.
 */
public record SanitizerProperties(
        boolean enabled,
        List<String> allowedTags,
        List<String> allowedAttributes,
        boolean stripUnsafeCss,
        boolean allowRelativeLinks
) {
    public SanitizerProperties {
        if (allowedTags == null) {
            allowedTags = List.of("b", "i", "em", "strong", "code", "pre", "blockquote", "ul", "ol", "li", "p", "br");
        }
        if (allowedAttributes == null) {
            allowedAttributes = List.of("href", "title");
        }
    }

    public static SanitizerProperties platformDefault() {
        return new SanitizerProperties(true, null, null, true, false);
    }
}
