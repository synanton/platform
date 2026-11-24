package org.synanton.syntology.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OntologyDigestTest {

    @Test
    void turtleReleaseDigestUsesNamedComponentHashes() {
        String digest = OntologyDigest.releaseDigest("turtle",
                Map.of("content", "abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(digest).isEqualTo(
                "20c980f72e7ac6dcb6262c3446ca34bf2c804066a44e3e7a1e438012626d270f");
    }

    @Test
    void componentOrderDoesNotChangeReleaseDigest() {
        String first = OntologyDigest.releaseDigest("hcl", Map.of(
                "tbox", "tbox".getBytes(StandardCharsets.UTF_8),
                "shapes", "shapes".getBytes(StandardCharsets.UTF_8),
                "schema_ir", "schema".getBytes(StandardCharsets.UTF_8)));
        String second = OntologyDigest.releaseDigest("hcl", Map.of(
                "schema_ir", "schema".getBytes(StandardCharsets.UTF_8),
                "shapes", "shapes".getBytes(StandardCharsets.UTF_8),
                "tbox", "tbox".getBytes(StandardCharsets.UTF_8)));

        assertThat(first).isEqualTo(second)
                .isEqualTo("42007dd712c4bcdc244055263481c6b04be8db389ca261ef58e0ada718d0e96c");
    }
}
