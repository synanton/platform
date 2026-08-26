# Ontology schemas (HCL)

Author ontology TBox and SHACL constraints in HashiCorp Configuration Language.
Syntology compiles HCL through a JSON IR into OWL Turtle (`ontology.ttl`) and SHACL (`shapes.ttl`).

## Layout

- `schema.hcl` - entry file
- `_common.hcl` - shared `ontology` metadata and base classes
- `include { path = "./file.hcl" }` - relative includes; cycles are rejected

## Load via Admin API

```bash
# Zip the directory (preserves includes) and POST
zip -r supply-chain.zip schema.hcl _common.hcl
curl -F version=1.1.0 -F file=@supply-chain.zip \
  http://localhost:8080/api/v1/admin/ontology/schemas

# Or load from a local Git checkout
curl -X POST http://localhost:8080/api/v1/admin/ontology/schemas/from-path \
  -H 'Content-Type: application/json' \
  -d '{"relativePath":"schemas/ontology","version":"1.1.0"}'
```

`syntology.schema.git-root` (default `.`) is the checkout root for `from-path`.
Preview without persisting: `POST /api/v1/admin/ontology/schemas/preview`.
