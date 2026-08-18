package org.synanton.synvault.spi;

import org.synanton.synvault.domain.AdapterDescriptor;
import org.synanton.synvault.domain.ContentRef;

import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

public interface ContentAdapter {
    AdapterDescriptor descriptor();
    Stream<ContentRef> list(String rootUri);
    InputStream open(ContentRef ref);
    Optional<java.time.Instant> lastModified(ContentRef ref);
}
