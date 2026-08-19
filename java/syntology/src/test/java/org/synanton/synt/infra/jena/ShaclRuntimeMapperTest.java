package org.synanton.synt.infra.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.synanton.synt.domain.model.schema.ClassSchema;
import org.synanton.synt.domain.model.schema.OntologyMeta;
import org.synanton.synt.domain.model.schema.OntologySchemaIr;
import org.synanton.synt.domain.model.schema.PropertySchema;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShaclRuntimeMapperTest {

    private final ShaclRuntimeMapper mapper = new ShaclRuntimeMapper();

    @Test
    void shouldValidateInstanceAgainstCompiledShapes() {
        OntologySchemaIr ir = new OntologySchemaIr(
                new OntologyMeta("demo", "http://example.org/ont#", "ex", "Demo", null),
                List.of(new ClassSchema(
                        "Person",
                        "Person",
                        List.of(),
                        List.of(new PropertySchema("name", "ex:name", "xsd:string", 1, 1))
                )),
                List.of()
        );

        Model shapesModel = mapper.toModel(ir);
        Shapes shapes = Shapes.parse(shapesModel);

        Model valid = ModelFactory.createDefaultModel();
        Resource alice = valid.createResource("http://example.org/alice");
        alice.addProperty(RDF.type, valid.createResource("http://example.org/ont#Person"));
        alice.addProperty(valid.createProperty("http://example.org/ont#name"), "Alice");
        assertThat(ShaclValidator.get().validate(shapes, valid.getGraph()).conforms()).isTrue();

        Model invalid = ModelFactory.createDefaultModel();
        Resource bob = invalid.createResource("http://example.org/bob");
        bob.addProperty(RDF.type, invalid.createResource("http://example.org/ont#Person"));
        assertThat(ShaclValidator.get().validate(shapes, invalid.getGraph()).conforms()).isFalse();
    }
}
