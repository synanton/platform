package org.synanton.common.resolution;

import java.util.Locale;

/** Conservative default: case-insensitive exact type match (no inference). */
public final class ExactTypeCompatibility implements TypeCompatibility {

    @Override
    public boolean compatible(String proposedType, String canonicalType, String ontologyVersion) {
        return proposedType != null && canonicalType != null
                && !proposedType.isBlank() && !canonicalType.isBlank()
                && proposedType.trim().toLowerCase(Locale.ROOT)
                .equals(canonicalType.trim().toLowerCase(Locale.ROOT));
    }
}
