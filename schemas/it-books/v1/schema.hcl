ontology "it-books" {
  namespace   = "http://synanton.example/ontology/it-books#"
  prefix      = "itb"
  label       = "IT Books Knowledge Ontology"
  description = "IT Books v1: bibliographic structure plus computing concepts and knowledge relationships"
}

class "Thing" {
  label = "Thing"
}

# Mention is deliberately NOT declared as a class. Mentions are source spans,
# not ontology entities; canonical types below must never be used to type them.
# Enforcement boundary: this prohibition is schema-level by absence and
# contract-level by documented layer separation (see ontology-v1.md), not a
# runtime constraint on arbitrary input.

# PREREQUISITE_FOR and a Prerequisite entity type are intentionally deferred.
# Do not add them without an A1 review decision.

# Abstract marker classes encode the union domains/ranges from #894 without
# weakening any predicate to a broad or unconstrained type.
class "TopicBearing" {
  label = "Topic-Bearing Source"
  super = ["Thing"]
}

class "TextDivision" {
  label = "Text Division"
  super = ["Thing"]
}

class "DefinedConcept" {
  label = "Defined Concept"
  super = ["Thing"]
}

class "UsesSubject" {
  label = "Uses Subject"
  super = ["Thing"]
}

class "UsesObject" {
  label = "Uses Object"
  super = ["Thing"]
}

class "DependencyNode" {
  label = "Dependency Node"
  super = ["Thing"]
}

class "PartOfSubject" {
  label = "Part-Of Subject"
  super = ["Thing"]
}

class "PartOfObject" {
  label = "Part-Of Object"
  super = ["Thing"]
}

class "Book" {
  label = "Book"
  super = ["Thing", "TopicBearing", "PartOfObject"]
}

class "Author" {
  label = "Author"
  super = ["Thing"]
}

class "Chapter" {
  label = "Chapter"
  super = ["Thing", "TopicBearing", "TextDivision", "UsesSubject", "PartOfSubject", "PartOfObject"]
}

class "Section" {
  label = "Section"
  super = ["Thing", "TopicBearing", "TextDivision", "UsesSubject", "PartOfSubject", "PartOfObject"]
}

class "Algorithm" {
  label = "Algorithm"
  super = ["Thing", "UsesSubject", "UsesObject", "DependencyNode"]
}

class "DataStructure" {
  label = "Data Structure"
  super = ["Thing", "UsesSubject", "UsesObject", "DependencyNode"]
}

class "Concept" {
  label = "Concept"
  super = ["Thing", "DefinedConcept", "UsesObject", "DependencyNode"]
}

class "Database" {
  label = "Database"
  super = ["Thing", "UsesSubject", "UsesObject", "DependencyNode"]
}

class "DistributedSystem" {
  label = "Distributed System"
  super = ["Thing", "UsesSubject", "UsesObject", "DependencyNode"]
}

class "Topic" {
  label = "Topic"
  super = ["Thing", "PartOfSubject"]
}

class "Definition" {
  label = "Definition"
  super = ["Thing", "DefinedConcept"]
}

relation "AUTHORED_BY" {
  label  = "authored by"
  domain = "Book"
  range  = "Author"
}

relation "HAS_CHAPTER" {
  label  = "has chapter"
  domain = "Book"
  range  = "Chapter"
}

relation "HAS_SECTION" {
  label  = "has section"
  domain = "Chapter"
  range  = "Section"
}

relation "COVERS_TOPIC" {
  label  = "covers topic"
  domain = "TopicBearing"
  range  = "Topic"
}

relation "DEFINES" {
  label  = "defines"
  domain = "TextDivision"
  range  = "DefinedConcept"
}

relation "USES" {
  label  = "uses"
  domain = "UsesSubject"
  range  = "UsesObject"
}

relation "DEPENDS_ON" {
  label  = "depends on"
  domain = "DependencyNode"
  range  = "DependencyNode"
}

relation "PART_OF" {
  label  = "part of"
  domain = "PartOfSubject"
  range  = "PartOfObject"
}
