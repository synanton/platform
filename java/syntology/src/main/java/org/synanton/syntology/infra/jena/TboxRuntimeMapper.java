package org.synanton.syntology.infra.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Component;
import org.synanton.syntology.domain.model.schema.ClassSchema;
import org.synanton.syntology.domain.model.schema.OntologyMeta;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;
import org.synanton.syntology.domain.model.schema.RelationSchema;
import org.synanton.syntology.infra.schema.SchemaUris;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@Component
public class TboxRuntimeMapper {

    public Model toModel(OntologySchemaIr ir) {
        Model model = ModelFactory.createDefaultModel();
        OntologyMeta meta = ir.ontology();
        String ns = SchemaUris.namespace(meta);
        String prefix = meta != null && meta.prefix() != null ? meta.prefix() : "ont";
        model.setNsPrefix(prefix, ns);
        model.setNsPrefix("rdf", SchemaUris.RDF);
        model.setNsPrefix("rdfs", SchemaUris.RDFS);
        model.setNsPrefix("owl", SchemaUris.OWL);

        Resource ontology = model.createResource(ns.replaceAll("#$", ""));
        ontology.addProperty(RDF.type, OWL.Ontology);
        if (meta != null && meta.label() != null) {
            ontology.addProperty(RDFS.label, meta.label());
        }
        if (meta != null && meta.description() != null) {
            ontology.addProperty(RDFS.comment, meta.description());
        }

        for (ClassSchema classSchema : ir.classes()) {
            Resource cls = model.createResource(SchemaUris.classIri(meta, classSchema.id()));
            cls.addProperty(RDF.type, OWL.Class);
            cls.addProperty(RDFS.label, classSchema.label() != null ? classSchema.label() : classSchema.id());
            for (String superId : classSchema.superTypes()) {
                cls.addProperty(RDFS.subClassOf, model.createResource(SchemaUris.classIri(meta, superId)));
            }
        }
        for (RelationSchema relation : ir.relations()) {
            Resource prop = model.createResource(SchemaUris.classIri(meta, relation.id()));
            prop.addProperty(RDF.type, RDF.Property);
            prop.addProperty(RDFS.label, relation.label() != null ? relation.label() : relation.id());
            if (relation.domain() != null) {
                prop.addProperty(RDFS.domain, model.createResource(SchemaUris.classIri(meta, relation.domain())));
            }
            if (relation.range() != null) {
                prop.addProperty(RDFS.range, model.createResource(SchemaUris.classIri(meta, relation.range())));
            }
        }
        return model;
    }

    public byte[] toTurtle(OntologySchemaIr ir) {
        Model model = toModel(ir);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        model.write(out, "TURTLE");
        return out.toByteArray();
    }

    public String toTurtleString(OntologySchemaIr ir) {
        return new String(toTurtle(ir), StandardCharsets.UTF_8);
    }
}
