package org.synanton.topology.infra.seeder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.synanton.topology.app.TopologyProperties;
import org.synanton.topology.domain.model.UserEntry;
import org.synanton.topology.domain.repository.AclGrantRepository;
import org.synanton.topology.domain.repository.UserRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Walks the ontology directory at startup and seeds topology.acl_grants from
 * actual POSIX file permissions. This makes the topology DB a queryable mirror
 * of the filesystem - the authoritative check at request time still goes through
 * the live FS bits.
 */
@Component
public class FilesystemAclSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FilesystemAclSeeder.class);

    private static final UUID DEMO_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository users;
    private final AclGrantRepository grants;
    private final TopologyProperties props;

    public FilesystemAclSeeder(UserRepository users, AclGrantRepository grants, TopologyProperties props) {
        this.users = users;
        this.grants = grants;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path root = Path.of(props.ontologyDir());
        if (!Files.exists(root)) {
            log.warn("Ontology directory {} does not exist - skipping FS seeding", root);
            return;
        }

        log.info("Seeding ACL grants from {}", root);
        grants.deleteBySource("FS_BOOTSTRAP");

        try {
            Files.walk(root).forEach(path -> seedPath(path, root));
        } catch (IOException e) {
            log.error("Error walking ontology directory {}: {}", root, e.getMessage());
        }

        log.info("FS ACL seeding complete");
    }

    private void seedPath(Path path, Path root) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
                log.debug("No POSIX attribute view for {}; skipping", path);
                return;
            }
            PosixFileAttributes attrs = view.readAttributes();
            Set<PosixFilePermission> perms = attrs.permissions();

            String resourcePath = root.relativize(path).toString();
            if (resourcePath.isBlank()) resourcePath = ".";

            // Owner uid - look up or create user record
            int ownerUid = ((Number) Files.getAttribute(path, "unix:uid")).intValue();
            String ownerName = attrs.owner().getName();
            upsertAndGrant(ownerName, ownerUid, resourcePath, ownerPermission(perms));

        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Skipping {} - cannot read POSIX attributes: {}", path, e.getMessage());
        }
    }

    private void upsertAndGrant(String username, int uid, String resourcePath, String permission) {
        if (permission == null) return;

        users.upsert(DEMO_ORG_ID, username, uid, java.util.List.of(uid));
        Optional<UserEntry> entry = users.findByUid(uid);
        if (entry.isEmpty()) return;

        grants.insert(DEMO_ORG_ID, entry.get().userId(), "USER", resourcePath, permission, "FS_BOOTSTRAP");
        log.debug("Granted {} {} -> {}", username, permission, resourcePath);
    }

    private static String ownerPermission(Set<PosixFilePermission> perms) {
        boolean r = perms.contains(PosixFilePermission.OWNER_READ);
        boolean w = perms.contains(PosixFilePermission.OWNER_WRITE);
        boolean x = perms.contains(PosixFilePermission.OWNER_EXECUTE);
        if (w || x) return "ADMIN";
        if (r) return "READ";
        return null;
    }
}
