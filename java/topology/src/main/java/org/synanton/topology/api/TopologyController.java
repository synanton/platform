package org.synanton.topology.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.common.error.NotFoundException;
import org.synanton.topology.domain.model.AclGrant;
import org.synanton.topology.domain.model.UserEntry;
import org.synanton.topology.domain.repository.AclGrantRepository;
import org.synanton.topology.domain.repository.UserRepository;
import org.synanton.topology.infra.seeder.FilesystemAclSeeder;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/topology")
public class TopologyController {

    private final UserRepository users;
    private final AclGrantRepository grants;
    private final FilesystemAclSeeder seeder;

    public TopologyController(UserRepository users, AclGrantRepository grants, FilesystemAclSeeder seeder) {
        this.users = users;
        this.grants = grants;
        this.seeder = seeder;
    }

    @GetMapping("/users")
    public List<UserEntry> listUsers() {
        return users.findAll();
    }

    @GetMapping("/users/{uid}/grants")
    public List<AclGrant> userGrants(@PathVariable int uid) {
        UserEntry user = users.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("No user with uid=" + uid));
        return grants.findBySubjectId(user.userId());
    }

    @PostMapping("/acl/reseed")
    public ResponseEntity<Map<String, String>> reseed() {
        seeder.run(null);
        return ResponseEntity.ok(Map.of("status", "reseeded"));
    }
}
