package org.synanton.synvault.api;

import org.synanton.synvault.domain.ContentPushResult;
import org.synanton.synvault.port.ContentPushPort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ContentPushController {

    private final ContentPushPort pushPort;

    public ContentPushController(ContentPushPort pushPort) {
        this.pushPort = pushPort;
    }

    @PostMapping(value = "/content/{tenant}", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> push(
            @PathVariable String tenant,
            @RequestHeader(value = "X-Source-Uri", required = false) String sourceUri,
            @RequestHeader(value = "Content-Type", required = false) String mimeType,
            @RequestBody byte[] body
    ) {
        ContentPushResult result = pushPort.write(tenant, body, sourceUri, mimeType);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content_ref_id", result.contentRefId().toString());
        response.put("sha256", result.sha256());
        response.put("size_bytes", result.sizeBytes());
        response.put("source_uri", sourceUri);
        return response;
    }
}
