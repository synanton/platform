package org.synanton.gateway.synthesis;

public class LlmContextSanitizer {

    public String sanitise(String context) {
        if (context == null) {
            return "";
        }
        return context
                .replaceAll("(?i)ignore previous instructions", "")
                .replaceAll("(?i)systemPromptOverrides", "")
                .replace("<script>", "")
                .replace("</script>", "");
    }
}
