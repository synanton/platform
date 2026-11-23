package org.synanton.extraction.engine.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Shared SHA-256 prompt digest/verification helpers (#831 contract). */
public final class PromptDigest {

    private PromptDigest() {}

    public static String digest(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns the rendered digest when compiled and rendered bytes match. */
    public static String verify(String compiledPrompt, String renderedPrompt) {
        String compiled = digest(compiledPrompt);
        String rendered = digest(renderedPrompt);
        if (!compiled.equals(rendered)) {
            throw new PromptDigestMismatchException(compiled, rendered);
        }
        return rendered;
    }
}
