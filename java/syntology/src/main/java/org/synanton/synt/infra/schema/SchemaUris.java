package org.synanton.synt.infra.schema;

import org.synanton.synt.domain.model.schema.OntologyMeta;

public final class SchemaUris {

    public static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    public static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    public static final String OWL = "http://www.w3.org/2002/07/owl#";
    public static final String SHACL = "http://www.w3.org/ns/shacl#";

    private SchemaUris() {
    }

    public static String namespace(OntologyMeta meta) {
        String ns = meta != null ? meta.namespace() : null;
        if (ns == null || ns.isBlank()) {
            return "http://synanton.example/ontology/default#";
        }
        if (ns.endsWith("#") || ns.endsWith("/")) {
            return ns;
        }
        return ns + "#";
    }

    public static String classIri(OntologyMeta meta, String id) {
        return expand(meta, id);
    }

    public static String expand(OntologyMeta meta, String qname) {
        if (qname == null || qname.isBlank()) {
            return null;
        }
        if (qname.startsWith("http://") || qname.startsWith("https://")) {
            return qname;
        }
        int colon = qname.indexOf(':');
        if (colon < 0) {
            return namespace(meta) + qname;
        }
        String prefix = qname.substring(0, colon);
        String local = qname.substring(colon + 1);
        return switch (prefix) {
            case "xsd" -> XSD + local;
            case "rdf" -> RDF + local;
            case "rdfs" -> RDFS + local;
            case "owl" -> OWL + local;
            case "sh" -> SHACL + local;
            default -> {
                String declared = meta != null ? meta.prefix() : null;
                if (declared != null && declared.equals(prefix)) {
                    yield namespace(meta) + local;
                }
                yield namespace(meta) + local;
            }
        };
    }
}
