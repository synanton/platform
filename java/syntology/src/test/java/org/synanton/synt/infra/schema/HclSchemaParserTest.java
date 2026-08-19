package org.synanton.synt.infra.schema;

import org.junit.jupiter.api.Test;
import org.synanton.synt.domain.model.schema.ClassSchema;
import org.synanton.synt.domain.model.schema.OntologySchemaIr;

import static org.assertj.core.api.Assertions.assertThat;

class HclSchemaParserTest {

    private final HclSchemaParser parser = new HclSchemaParser();

    @Test
    void shouldParseLabeledClassAndPropertyIntoIr() {
        String hcl = """
                ontology "supply-chain" {
                  namespace = "http://synanton.example/ontology/supply-chain#"
                  prefix    = "sc"
                }
                class "Supplier" {
                  super = ["Organization"]
                  property "name" {
                    path      = "sc:name"
                    datatype  = "xsd:string"
                    min_count = 1
                    max_count = 1
                  }
                }
                """;

        OntologySchemaIr ir = parser.parse(hcl).ir();

        assertThat(ir).isEqualTo(new OntologySchemaIr(
                ir.ontology(),
                ir.classes(),
                ir.relations()
        ));
        assertThat(ir.ontology().id()).isEqualTo("supply-chain");
        assertThat(ir.ontology().prefix()).isEqualTo("sc");
        assertThat(ir.classes()).hasSize(1);
        ClassSchema supplier = ir.classes().getFirst();
        assertThat(supplier).isEqualTo(new ClassSchema(
                "Supplier",
                "Supplier",
                java.util.List.of("Organization"),
                supplier.properties()
        ));
        assertThat(supplier.properties().getFirst().minCount()).isEqualTo(1);
        assertThat(supplier.properties().getFirst().datatype()).isEqualTo("xsd:string");
    }
}
