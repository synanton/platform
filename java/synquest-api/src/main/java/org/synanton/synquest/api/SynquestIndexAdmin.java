package org.synanton.synquest.api;

import java.util.concurrent.CompletionStage;

/** Index-lifecycle port (proposal §10.3). */
public interface SynquestIndexAdmin {

    CompletionStage<Void> ensureSchema(SchemaOptions options);

    CompletionStage<Void> rebuild(RebuildOptions options);

    CompletionStage<IndexStatus> status();
}
