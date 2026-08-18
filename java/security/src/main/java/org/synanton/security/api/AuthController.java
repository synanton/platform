package org.synanton.security.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.security.service.AuthService;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        AuthService.TokenResponse resp = authService.login(req.username(), req.password());
        return ResponseEntity.ok(new LoginResponse(resp.token(), resp.expiresIn()));
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@RequestBody ValidateRequest req) {
        SubjectAssertion assertion = authService.validate(req.token());
        return ResponseEntity.ok(new ValidateResponse(
                true,
                assertion.subject(),
                assertion.uid(),
                assertion.gids(),
                "JWT",
                null
        ));
    }

    /** Extended validate endpoint: accepts Authorization header for both JWT and API keys. */
    @PostMapping("/validate-header")
    public ResponseEntity<ValidatedIdentityResponse> validateHeader(
            @RequestHeader("Authorization") String authHeader) {
        var identity = authService.validateHeader(authHeader);
        return ResponseEntity.ok(new ValidatedIdentityResponse(
                identity.subjectId(),
                identity.tenantId(),
                identity.identityProfile(),
                java.util.List.of(identity.scopes()),
                identity.keyId()
        ));
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, long expiresIn) {}
    public record ValidateRequest(String token) {}
    public record ValidateResponse(boolean valid, String subject, int uid, java.util.List<Integer> gid,
                                   String identityProfile, String keyId) {}
    public record ValidatedIdentityResponse(String subjectId, String tenantId, String identityProfile,
                                            java.util.List<String> scopes, String keyId) {}
}
