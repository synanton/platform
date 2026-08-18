package org.synanton.common.security.sanitizer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "synanton.sanitizer")
public class SanitizerConfigurationProperties {

    private boolean enabled = true;
    private List<String> allowedTags = new ArrayList<>(List.of(
            "b", "i", "em", "strong", "code", "pre", "blockquote", "ul", "ol", "li", "p", "br"));
    private List<String> allowedAttributes = new ArrayList<>(List.of("href", "title"));
    private boolean stripUnsafeCss = true;
    private boolean allowRelativeLinks = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedTags() {
        return allowedTags;
    }

    public void setAllowedTags(List<String> allowedTags) {
        this.allowedTags = allowedTags;
    }

    public List<String> getAllowedAttributes() {
        return allowedAttributes;
    }

    public void setAllowedAttributes(List<String> allowedAttributes) {
        this.allowedAttributes = allowedAttributes;
    }

    public boolean isStripUnsafeCss() {
        return stripUnsafeCss;
    }

    public void setStripUnsafeCss(boolean stripUnsafeCss) {
        this.stripUnsafeCss = stripUnsafeCss;
    }

    public boolean isAllowRelativeLinks() {
        return allowRelativeLinks;
    }

    public void setAllowRelativeLinks(boolean allowRelativeLinks) {
        this.allowRelativeLinks = allowRelativeLinks;
    }

    public SanitizerProperties toSanitizerProperties() {
        return new SanitizerProperties(enabled, allowedTags, allowedAttributes, stripUnsafeCss, allowRelativeLinks);
    }
}
