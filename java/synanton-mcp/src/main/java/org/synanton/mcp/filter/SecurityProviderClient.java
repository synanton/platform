package org.synanton.mcp.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Validates a bearer against the in-repo security service
 * ({@code POST /auth/validate-header}). Field shapes follow the service's
 * {@code ValidatedIdentityResponse} exactly: {@code subjectId}, {@code tenantId},
 * {@code identityProfile}, {@code scopes}, {@code keyId}. There is no expiry
 * on the wire response, so TTL capping is configured-only (TODO if the
 * service ever emits an expiry).
 */
@Component
public class SecurityProviderClient {
    private static final Logger log = LoggerFactory.getLogger(SecurityProviderClient.class);

    private final RestClient http;

    public SecurityProviderClient(RestClient.Builder builder,
                                  @Value("${synanton-mcp.security-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public sealed interface Result {
        record Ok(McpIdentityContext.Identity identity) implements Result {}
        record Invalid(String reason) implements Result {}
        record Upstream(String reason) implements Result {} // 5xx or transport — never cached
    }

    public Result validate(String bearer) {
        // Note: retrieve() throws on non-2xx, so status mapping lives in the
        // catch below; the 2xx path only handles the body.
        try {
            ResponseEntity<ValidatedIdentity> r = http.post()
                    .uri("/auth/validate-header")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .retrieve()
                    .toEntity(ValidatedIdentity.class);

            ValidatedIdentity v = r.getBody();
            if (v == null || v.tenantId() == null || v.tenantId().isBlank()) {
                log.warn("security service returned no tenant_id");
                return new Result.Invalid("no tenant context");
            }
            return new Result.Ok(new McpIdentityContext.Identity(
                    v.tenantId(), v.subjectId(), v.scopes()));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                return new Result.Invalid("rejected by provider");
            }
            return new Result.Upstream("status " + e.getStatusCode());
        } catch (RestClientException e) {
            log.error("security service call failed", e);
            return new Result.Upstream(e.getMessage());
        }
    }

    record ValidatedIdentity(String subjectId, String tenantId, String identityProfile,
                             List<String> scopes, String keyId) {}
}
