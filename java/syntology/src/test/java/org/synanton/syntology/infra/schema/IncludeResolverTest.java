package org.synanton.syntology.infra.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncludeResolverTest {

    private final IncludeResolver resolver = new IncludeResolver(new HclSchemaParser());

    @Test
    void shouldMergeIncludedClasses(@TempDir Path dir) throws Exception {
        copy("schemas/_common.hcl", dir.resolve("_common.hcl"));
        copy("schemas/schema.hcl", dir.resolve("schema.hcl"));

        OntologySchemaIr ir = resolver.resolve(dir, dir.resolve("schema.hcl"));

        assertThat(ir.ontology().id()).isEqualTo("supply-chain");
        assertThat(ir.classes().stream().map(c -> c.id()).toList()).contains("Thing", "Supplier", "Product");
        assertThat(ir.relations().stream().map(r -> r.id()).toList()).contains("hasSupplier");
    }

    @Test
    void shouldRejectCyclicIncludes(@TempDir Path dir) throws Exception {
        copy("schemas/cycle-a.hcl", dir.resolve("cycle-a.hcl"));
        copy("schemas/cycle-b.hcl", dir.resolve("cycle-b.hcl"));

        assertThatThrownBy(() -> resolver.resolve(dir, dir.resolve("cycle-a.hcl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cyclic include");
    }

    private static void copy(String resource, Path target) throws Exception {
        try (var in = IncludeResolverTest.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
