package org.synanton.synquest.api;

/** Options for {@link SynquestIndexAdmin#ensureSchema}. */
public record SchemaOptions(boolean recreate) {
}
