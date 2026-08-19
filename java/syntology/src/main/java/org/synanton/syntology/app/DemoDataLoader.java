package org.synanton.syntology.app;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.port.out.MetadataRepository;
import org.synanton.syntology.domain.port.out.OntologyAdapter;
import org.synanton.syntology.domain.port.out.EventPublisher;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
public class DemoDataLoader {

    private static final String SEED_VERSION = "1.0.0";

    private final MetadataRepository metadataRepository;
    private final OntologyAdapter ontologyAdapter;
    private final EventPublisher eventPublisher;
    private final SyntologyProperties properties;

    public DemoDataLoader(
            MetadataRepository metadataRepository,
            OntologyAdapter ontologyAdapter,
            EventPublisher eventPublisher,
            SyntologyProperties properties
    ) {
        this.metadataRepository = metadataRepository;
        this.ontologyAdapter = ontologyAdapter;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() throws IOException {
        if (!metadataRepository.isEmpty()) {
            return;
        }
        String tenant = properties.tenant().defaultId();
        TenantContext.setAnonymous(tenant);
        try {
            seed(tenant);
        } finally {
            TenantContext.clear();
        }
    }

    private void seed(String tenant) throws IOException {
        ClassPathResource resource = new ClassPathResource("sample-ontology.ttl");
        byte[] turtle = resource.getInputStream().readAllBytes();
        ontologyAdapter.persistTurtle(tenant, SEED_VERSION, turtle);
        String graphUri = properties.storage().jena().path() + "/" + tenant + "/" + SEED_VERSION;
        metadataRepository.insert(new OntologyVersion(
                UUID.randomUUID(),
                tenant,
                SEED_VERSION,
                "Supply Chain Ontology",
                "Demo seed ontology for standalone Syntology module",
                graphUri,
                "ACTIVE",
                Instant.now()
        ));
        eventPublisher.publish("ONTOLOGY_CREATED", "{\"version\":\"" + SEED_VERSION + "\"}");
    }
}
