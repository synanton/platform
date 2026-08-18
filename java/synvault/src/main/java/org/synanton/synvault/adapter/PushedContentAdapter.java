package org.synanton.synvault.adapter;

import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.domain.AdapterDescriptor;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;
import org.synanton.synvault.spi.ContentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class PushedContentAdapter implements ContentAdapter {

    private static final Logger log = LoggerFactory.getLogger(PushedContentAdapter.class);

    private final ObjectStorePort objectStore;
    private final SynvaultObjectStoreProperties storeProps;

    public PushedContentAdapter(ObjectStorePort objectStore, SynvaultObjectStoreProperties storeProps) {
        this.objectStore = objectStore;
        this.storeProps = storeProps;
    }

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor("synvault", "Pushed Synvault content", "1.0");
    }

    @Override
    public Stream<ContentRef> list(String rootUri) {
        String stripped = rootUri.startsWith("synvault://") ? rootUri.substring("synvault://".length()) : rootUri;
        int slash = stripped.indexOf('/');
        if (slash < 0) {
            log.warn("synvault URI missing tenant/id: {}", rootUri);
            return Stream.empty();
        }
        String tenant = stripped.substring(0, slash);
        String ids = stripped.substring(slash + 1);
        List<ContentRef> refs = new ArrayList<>();
        for (String id : ids.split(",")) {
            String uuid = id.trim();
            if (uuid.isEmpty()) {
                continue;
            }
            refs.add(new ContentRef(
                    "synvault",
                    "synvault://" + tenant + "/" + uuid,
                    "application/octet-stream",
                    0,
                    Instant.now()
            ));
        }
        return refs.stream();
    }

    @Override
    public InputStream open(ContentRef ref) {
        String uri = ref.uri();
        String stripped = uri.startsWith("synvault://") ? uri.substring("synvault://".length()) : uri;
        int slash = stripped.indexOf('/');
        String tenant = slash < 0 ? "" : stripped.substring(0, slash);
        String id = slash < 0 ? stripped : stripped.substring(slash + 1);
        return objectStore.getObject(storeProps.hotBucket(), tenant + "/" + id);
    }

    @Override
    public Optional<Instant> lastModified(ContentRef ref) {
        return Optional.empty();
    }
}
