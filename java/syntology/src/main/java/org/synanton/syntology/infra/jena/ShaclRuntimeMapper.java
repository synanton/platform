package org.synanton.syntology.infra.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;
import org.synanton.syntology.domain.model.schema.ClassSchema;
import org.synanton.syntology.domain.model.schema.NodeShape;
import org.synanton.syntology.domain.model.schema.OntologyMeta;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;
import org.synanton.syntology.domain.model.schema.PropertyConstraint;
import org.synanton.syntology.domain.model.schema.PropertySchema;
import org.synanton.syntology.infra.schema.SchemaUris;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShaclRuntimeMapper {

    public List<NodeShape> toRuntimeShapes(OntologySchemaIr ir) {
        OntologyMeta meta = ir.ontology();
        List<NodeShape> shapes = new ArrayList<>();
        for (ClassSchema classSchema : ir.classes()) {
            String classIri = SchemaUris.classIri(meta, classSchema.id());
            List<PropertyConstraint> constraints = new ArrayList<>();
            for (PropertySchema property : classSchema.properties()) {
                String path = property.path() != null ? property.path() : property.name();
                constraints.add(new PropertyConstraint(
                        SchemaUris.expand(meta, path),
                        SchemaUris.expand(meta, property.datatype()),
                        property.minCount(),
                        property.maxCount()
                ));
            }
            shapes.add(new NodeShape(classIri + "Shape", classIri, constraints));
        }
        return List.copyOf(shapes);
    }

    public Model toModel(OntologySchemaIr ir) {
        List<NodeShape> shapes = toRuntimeShapes(ir);
        Model model = ModelFactory.createDefaultModel();
        OntologyMeta meta = ir.ontology();
        String ns = SchemaUris.namespace(meta);
        String prefix = meta != null && meta.prefix() != null ? meta.prefix() : "ont";
        model.setNsPrefix(prefix, ns);
        model.setNsPrefix("sh", SchemaUris.SHACL);
        model.setNsPrefix("xsd", SchemaUris.XSD);
        Property shNodeShape = model.createProperty(SchemaUris.SHACL + "NodeShape");
        Property shTargetClass = model.createProperty(SchemaUris.SHACL + "targetClass");
        Property shProperty = model.createProperty(SchemaUris.SHACL + "property");
        Property shPath = model.createProperty(SchemaUris.SHACL + "path");
        Property shDatatype = model.createProperty(SchemaUris.SHACL + "datatype");
        Property shMinCount = model.createProperty(SchemaUris.SHACL + "minCount");
        Property shMaxCount = model.createProperty(SchemaUris.SHACL + "maxCount");
        for (NodeShape shape : shapes) {
            Resource node = model.createResource(shape.iri());
            node.addProperty(RDF.type, shNodeShape);
            node.addProperty(shTargetClass, model.createResource(shape.targetClassIri()));
            for (PropertyConstraint constraint : shape.properties()) {
                Resource property = model.createResource();
                if (constraint.pathIri() != null) {
                    property.addProperty(shPath, model.createResource(constraint.pathIri()));
                }
                if (constraint.datatypeIri() != null) {
                    property.addProperty(shDatatype, model.createResource(constraint.datatypeIri()));
                }
                if (constraint.minCount() != null) {
                    property.addLiteral(shMinCount, constraint.minCount());
                }
                if (constraint.maxCount() != null) {
                    property.addLiteral(shMaxCount, constraint.maxCount());
                }
                node.addProperty(shProperty, property);
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
