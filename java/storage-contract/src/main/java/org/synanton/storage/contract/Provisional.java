package org.synanton.storage.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API surface as provisional under YDB-POC-010 (Architecture 1.0 §14–15 gate).
 *
 * <p>Phase 0B may proceed while Designs 1.27 (Eventing) and 1.32 (Operation/error
 * contracts) are unfrozen, but anything that will eventually bind to 1.27 events or
 * 1.32 error contracts carries this annotation and <strong>must not</strong> be treated
 * as a committed domain API. Each use is tracked in
 * {@code docs/implementation/ydb-poc/011-provisional-followup.md} and re-validated
 * at 1.27/1.32 freeze — at which point the annotation is removed or the shape changes
 * with no deprecation obligation (throwaway scope, YDB-POC-010).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PACKAGE})
public @interface Provisional {
    /** Design that must freeze before this surface becomes stable, e.g. {@code "1.27"}. */
    String value();

    /** Why the surface is provisional. */
    String reason() default "";
}
