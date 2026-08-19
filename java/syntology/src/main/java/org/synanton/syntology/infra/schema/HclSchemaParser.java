package org.synanton.syntology.infra.schema;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.bertramlabs.plugins.hcl4j.HCLParserException;
import org.springframework.stereotype.Component;
import org.synanton.syntology.domain.model.schema.ClassSchema;
import org.synanton.syntology.domain.model.schema.OntologyMeta;
import org.synanton.syntology.domain.model.schema.OntologySchemaIr;
import org.synanton.syntology.domain.model.schema.PropertySchema;
import org.synanton.syntology.domain.model.schema.RelationSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HclSchemaParser {

    public ParsedHcl parseFile(Path file) {
        try {
            String source = Files.readString(file);
            return parse(source);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read HCL file: " + file, ex);
        }
    }

    public ParsedHcl parse(String source) {
        Map<String, Object> tree;
        try {
            tree = new HCLParser().parse(source);
        } catch (HCLParserException | IOException ex) {
            throw new IllegalArgumentException("Invalid HCL: " + ex.getMessage(), ex);
        }
        if (tree == null) {
            tree = Map.of();
        }
        return new ParsedHcl(toIr(tree), extractIncludes(tree));
    }

    private OntologySchemaIr toIr(Map<String, Object> tree) {
        OntologyMeta meta = parseOntology(tree.get("ontology"));
        List<ClassSchema> classes = new ArrayList<>();
        HclMaps.labeledBlocks(tree.get("class")).forEach((id, body) -> classes.add(toClass(id, body)));
        List<RelationSchema> relations = new ArrayList<>();
        HclMaps.labeledBlocks(tree.get("relation")).forEach((id, body) -> relations.add(toRelation(id, body)));
        return new OntologySchemaIr(meta, classes, relations);
    }

    private OntologyMeta parseOntology(Object raw) {
        Map<String, Map<String, Object>> blocks = HclMaps.labeledBlocks(raw);
        if (blocks.isEmpty()) {
            Map<String, Object> attrs = HclMaps.asMap(raw);
            if (attrs.isEmpty()) {
                return new OntologyMeta(null, null, null, null, null);
            }
            return new OntologyMeta(
                    HclMaps.asString(attrs.get("id")),
                    HclMaps.asString(attrs.get("namespace")),
                    HclMaps.asString(attrs.get("prefix")),
                    HclMaps.asString(attrs.get("label")),
                    HclMaps.asString(attrs.get("description"))
            );
        }
        Map.Entry<String, Map<String, Object>> first = blocks.entrySet().iterator().next();
        Map<String, Object> attrs = first.getValue();
        return new OntologyMeta(
                first.getKey(),
                HclMaps.asString(attrs.get("namespace")),
                HclMaps.asString(attrs.get("prefix")),
                HclMaps.asString(attrs.get("label")),
                HclMaps.asString(attrs.get("description"))
        );
    }

    private ClassSchema toClass(String id, Map<String, Object> body) {
        Object superRaw = body.containsKey("super") ? body.get("super") : body.get("supers");
        List<String> supers = HclMaps.asStringList(superRaw);
        List<PropertySchema> properties = new ArrayList<>();
        HclMaps.labeledBlocks(body.get("property")).forEach((name, propBody) -> properties.add(new PropertySchema(
                name,
                HclMaps.asString(propBody.get("path")),
                HclMaps.asString(propBody.get("datatype")),
                HclMaps.asInteger(first(propBody, "min_count", "minCount")),
                HclMaps.asInteger(first(propBody, "max_count", "maxCount"))
        )));
        String label = HclMaps.asString(body.get("label"));
        return new ClassSchema(id, label != null ? label : id, supers, properties);
    }

    private RelationSchema toRelation(String id, Map<String, Object> body) {
        String label = HclMaps.asString(body.get("label"));
        return new RelationSchema(
                id,
                label != null ? label : id,
                HclMaps.asString(body.get("domain")),
                HclMaps.asString(body.get("range"))
        );
    }

    private List<String> extractIncludes(Map<String, Object> tree) {
        List<String> includes = new ArrayList<>();
        Object include = tree.get("include");
        if (include instanceof String path) {
            includes.add(path);
        } else {
            Map<String, Map<String, Object>> labeled = HclMaps.labeledBlocks(include);
            if (!labeled.isEmpty() && include instanceof Map<?, ?> map && HclMaps.asMap(include).containsKey("path")) {
                String path = HclMaps.asString(HclMaps.asMap(include).get("path"));
                if (path != null) {
                    includes.add(path);
                }
            } else if (!labeled.isEmpty()) {
                labeled.values().forEach(body -> {
                    String path = HclMaps.asString(body.get("path"));
                    if (path != null) {
                        includes.add(path);
                    }
                });
            } else {
                for (Object item : HclMaps.asList(include)) {
                    if (item instanceof String path) {
                        includes.add(path);
                    } else {
                        String path = HclMaps.asString(HclMaps.asMap(item).get("path"));
                        if (path != null) {
                            includes.add(path);
                        }
                    }
                }
            }
        }
        Object includesAttr = tree.get("includes");
        includes.addAll(HclMaps.asStringList(includesAttr));
        return includes;
    }

    private static Object first(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null) {
                return body.get(key);
            }
        }
        return null;
    }

    public record ParsedHcl(OntologySchemaIr ir, List<String> includes) {
    }
}
