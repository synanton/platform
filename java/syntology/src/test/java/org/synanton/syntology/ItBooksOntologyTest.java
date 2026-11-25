package org.synanton.syntology;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.synanton.syntology.infra.schema.HclSchemaParser;

class ItBooksOntologyTest {

    @Test
    void parsesVersionedItBooksVocabularyAndUnionMarkers() {
        Path root = Path.of(System.getProperty("user.dir"));
        Path artifact = root.resolve("../../schemas/it-books/v1/schema.hcl").normalize();
        if (!Files.exists(artifact)) {
            artifact = root.resolve("schemas/it-books/v1/schema.hcl");
        }
        assertThat(artifact).exists();

        var parsed = new HclSchemaParser().parseFile(artifact).ir();
        assertThat(parsed.ontology().id()).isEqualTo("it-books");
        assertThat(parsed.classes()).extracting(classSchema -> classSchema.id())
                .contains("Book", "Author", "Chapter", "Section", "Algorithm", "DataStructure",
                        "Concept", "Database", "DistributedSystem", "Topic", "Definition");
        assertThat(parsed.classes()).extracting(classSchema -> classSchema.id())
                .doesNotContain("Mention");
        assertThat(parsed.relations()).hasSize(8);
        assertThat(parsed.relations()).allSatisfy(relation -> {
            assertThat(relation.domain()).isNotBlank();
            assertThat(relation.range()).isNotBlank();
        });
        assertThat(parsed.relations()).filteredOn(relation -> relation.id().equals("COVERS_TOPIC"))
                .singleElement().satisfies(relation -> {
                    assertThat(relation.domain()).isEqualTo("TopicBearing");
                    assertThat(relation.range()).isEqualTo("Topic");
                });
        assertThat(parsed.classes()).filteredOn(classSchema -> classSchema.id().equals("Book"))
                .singleElement().satisfies(classSchema ->
                        assertThat(classSchema.superTypes()).contains("TopicBearing", "PartOfObject"));
    }
}
