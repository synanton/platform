package org.synanton.contentcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentReferenceTest {

    @Test
    void encodeParseRoundTrip() {
        ContentReference ref = new ContentReference(
                "acme", UUID.randomUUID().toString(), "sha256:abc123", 1, 3);
        ContentReference parsed = ContentReference.parse(ref.encode());
        assertThat(parsed).isEqualTo(ref);
    }

    @Test
    void rejectsMissingVersionPrefix() {
        assertThatThrownBy(() -> ContentReference.parse("tenant_id=acme"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingTenantId() {
        assertThatThrownBy(() -> ContentReference.parse("v1:artifact_id=x&digest=sha256:y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapesSpecialCharactersInTenantId() {
        ContentReference ref = new ContentReference(
                "acme&co", UUID.randomUUID().toString(), "sha256:abc123", 1, 1);
        assertThat(ContentReference.parse(ref.encode()).tenantId()).isEqualTo("acme&co");
    }

    @Test
    void encodedReferenceStaysWithin512Chars() {
        ContentReference ref = new ContentReference(
                "acme", UUID.randomUUID().toString(), "sha256:abc123", 1, 1);
        assertThat(ref.encode().length()).isLessThanOrEqualTo(ContentReference.MAX_LENGTH);
    }
}
