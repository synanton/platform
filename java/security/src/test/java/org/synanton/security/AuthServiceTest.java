package org.synanton.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.security.app.SecurityProperties;
import org.synanton.security.idp.HtpasswdBackend;
import org.synanton.security.service.AuthService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final String SECRET = "synanton-test-secret-key-32bytes!!";

    @Test
    void login_valid_returnsToken(@TempDir Path dir) throws IOException {
        Path htpasswd = createHtpasswd(dir, "alice", "secret", 1001, List.of(1001, 2000));
        AuthService service = buildService(htpasswd.toString());

        AuthService.TokenResponse resp = service.login("alice", "secret");
        assertThat(resp.token()).isNotBlank();
        assertThat(resp.expiresIn()).isEqualTo(3600L);
    }

    @Test
    void login_wrongPassword_throws(@TempDir Path dir) throws IOException {
        Path htpasswd = createHtpasswd(dir, "alice", "secret", 1001, List.of(1001));
        AuthService service = buildService(htpasswd.toString());

        assertThatThrownBy(() -> service.login("alice", "wrong"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void validate_roundTrip(@TempDir Path dir) throws IOException {
        Path htpasswd = createHtpasswd(dir, "bob", "pass", 1002, List.of(1002, 3000));
        AuthService service = buildService(htpasswd.toString());

        String token = service.login("bob", "pass").token();
        SubjectAssertion assertion = service.validate(token);

        assertThat(assertion.subject()).isEqualTo("bob");
        assertThat(assertion.uid()).isEqualTo(1002);
        assertThat(assertion.gids()).containsExactly(1002, 3000);
        assertThat(assertion.tenantId()).isEqualTo("demo");
    }

    private static Path createHtpasswd(Path dir, String username, String plainPassword, int uid, List<Integer> gids) throws IOException {
        String hash = BCrypt.withDefaults().hashToString(10, plainPassword.toCharArray());
        String gidStr = gids.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
        Path file = dir.resolve("users");
        Files.writeString(file, username + ":" + hash + ":" + uid + ":" + gidStr + "\n");
        return file;
    }

    private static AuthService buildService(String htpasswdPath) {
        SecurityProperties props = SecurityProperties.of(
                new SecurityProperties.Idp("htpasswd", htpasswdPath),
                new SecurityProperties.Jwt(SECRET, 3600L, "demo")
        );
        return new AuthService(new HtpasswdBackend(htpasswdPath), props);
    }
}
