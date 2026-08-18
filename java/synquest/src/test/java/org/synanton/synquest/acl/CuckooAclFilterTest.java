package org.synanton.synquest.acl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CuckooAclFilterTest {

    @Test
    void shouldContainInsertedGrantAndDropRevokedGrant() {
        CuckooAclFilter filter = new CuckooAclFilter(64);
        filter.insert("user:alice", "doc-1");
        assertThat(filter.contains("user:alice", "doc-1")).isTrue();
        assertThat(filter.contains("user:bob", "doc-1")).isFalse();
        filter.delete("user:alice", "doc-1");
        assertThat(filter.contains("user:alice", "doc-1")).isFalse();
    }
}
