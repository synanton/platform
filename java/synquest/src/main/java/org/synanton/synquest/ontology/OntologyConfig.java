package org.synanton.synquest.ontology;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class OntologyConfig {

    private final String ontologyPath;
    private final String expectedSha;

    public OntologyConfig(
            @Value("${ontology.v2.path}") String ontologyPath,
            @Value("${ontology.v2.sha256}") String expectedSha) {
        this.ontologyPath = ontologyPath;
        this.expectedSha = expectedSha;
    }

    @Bean
    public OntologyMapper ontologyMapper() throws IOException {
        return OntologyMapper.loadFrom(Path.of(ontologyPath), expectedSha);
    }
}
