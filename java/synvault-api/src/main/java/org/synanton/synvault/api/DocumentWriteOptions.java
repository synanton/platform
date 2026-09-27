package org.synanton.synvault.api;

/** Options for {@link SynvaultStore#putDocument}. */
public record DocumentWriteOptions(boolean upsert) {
    public static DocumentWriteOptions upserting() {
        return new DocumentWriteOptions(true);
    }

    public static DocumentWriteOptions createOnly() {
        return new DocumentWriteOptions(false);
    }
}
