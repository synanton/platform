package org.synanton.synvault.port;

import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.spi.ContentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ContentPullPort {

    private static final Logger log = LoggerFactory.getLogger(ContentPullPort.class);
    private final Map<String, ContentAdapter> adapters;

    public ContentPullPort(List<ContentAdapter> adapters) {
        this.adapters = adapters.stream()
            .collect(Collectors.toMap(a -> a.descriptor().scheme(), Function.identity()));
        log.info("Registered content adapters: {}", this.adapters.keySet());
    }

    public Stream<ContentRef> discover(String tenant, String rootUri) {
        String scheme = rootUri.startsWith("file://") ? "file" : rootUri.split(":")[0];
        ContentAdapter adapter = adapters.get(scheme);
        if (adapter == null) {
            log.warn("No adapter for scheme '{}' (tenant={})", scheme, tenant);
            return Stream.empty();
        }
        return adapter.list(rootUri);
    }

    public InputStream open(String tenant, ContentRef ref) {
        ContentAdapter adapter = adapters.get(ref.scheme());
        if (adapter == null) throw new IllegalArgumentException("No adapter for scheme: " + ref.scheme());
        return adapter.open(ref);
    }
}
