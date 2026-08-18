package org.synanton.topology;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.synanton.topology.domain.model.AclGrant;
import org.synanton.topology.domain.model.UserEntry;
import org.synanton.topology.infra.jdbc.JdbcAclGrantRepository;
import org.synanton.topology.infra.jdbc.JdbcUserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcUserRepository.class, JdbcAclGrantRepository.class})
@Sql("/db/schema-test.sql")
class TopologyIntegrationTest {

    static final UUID DEMO_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired JdbcUserRepository users;
    @Autowired JdbcAclGrantRepository grants;

    @BeforeEach
    void cleanGrants() {
        grants.deleteBySource("FS_BOOTSTRAP");
        grants.deleteBySource("MANUAL");
    }

    @Test
    void upsertUser_thenFindByUid() {
        users.upsert(DEMO_ORG, "alice", 1001, List.of(1001, 2000));

        Optional<UserEntry> found = users.findByUid(1001);
        assertThat(found).isPresent();
        assertThat(found.get().username()).isEqualTo("alice");
        assertThat(found.get().gids()).containsExactly(1001, 2000);
    }

    @Test
    void insertGrant_thenFindBySubject() {
        users.upsert(DEMO_ORG, "bob", 1002, List.of(1002, 3000));
        UserEntry bob = users.findByUid(1002).orElseThrow();

        grants.insert(DEMO_ORG, bob.userId(), "USER", "demo/ontology.ttl", "READ", "FS_BOOTSTRAP");

        List<AclGrant> found = grants.findBySubjectId(bob.userId());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).resourcePath()).isEqualTo("demo/ontology.ttl");
        assertThat(found.get(0).permission()).isEqualTo("READ");
    }

    @Test
    void deleteBySource_clearsBootstrapGrants() {
        users.upsert(DEMO_ORG, "charlie", 1003, List.of(1003));
        UserEntry charlie = users.findByUid(1003).orElseThrow();
        grants.insert(DEMO_ORG, charlie.userId(), "USER", "demo/", "ADMIN", "FS_BOOTSTRAP");

        grants.deleteBySource("FS_BOOTSTRAP");

        assertThat(grants.findBySubjectId(charlie.userId())).isEmpty();
    }
}
