ontology "it-books" {
  namespace   = "http://synanton.example/ontology/it-books-test#"
  prefix      = "itbt"
  label       = "IT Books Test Ontology"
  description = "Minimal IT Books ontology for per-release activation tests"
}

class "Book" {
  label = "Book"
}

class "Concept" {
  label = "Concept"
}

relation "covers" {
  label = "covers"
  domain = ["Book"]
  range  = ["Concept"]
}
