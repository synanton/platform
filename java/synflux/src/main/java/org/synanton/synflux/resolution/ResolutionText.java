package org.synanton.synflux.resolution;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic normalization only; no fuzzy matching or punctuation stripping. */
public final class ResolutionText {
    private ResolutionText() {}

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC)
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    }
}
