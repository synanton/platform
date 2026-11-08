#!/usr/bin/env python3
"""Manual canonical-entity projection: Neo4j (:Entity) -> Synquest Lucene.

Neo4j remains the source of truth. This utility is for 4b.1 verification and
operator backfills; automated eventing can replace it later.

Env:
  NEO4J_HTTP_URL     default http://localhost:7474
  NEO4J_DATABASE     default neo4j
  NEO4J_USER/PASSWORD (optional for auth-disabled test containers)
  SYNQUEST_URL       default http://localhost:8083
  SYNANTON_JWT_SECRET (>=32 chars; used to mint a tenant token)
  TENANT             default demo
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.request


def base64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def mint_token(tenant: str, secret: str) -> str:
    header = base64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = base64url(json.dumps({
        "sub": "entity-projection",
        "tenant_id": tenant,
        "jti": str(time.time_ns()),
        "exp": int(time.time()) + 300,
    }, separators=(",", ":")).encode())
    signature = base64url(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def neo4j_entities(tenant: str) -> list[dict]:
    base = os.environ.get("NEO4J_HTTP_URL", "http://localhost:7474").rstrip("/")
    database = os.environ.get("NEO4J_DATABASE", "neo4j")
    user = os.environ.get("NEO4J_USER", "")
    password = os.environ.get("NEO4J_PASSWORD", "")
    statement = (
        "MATCH (e:Entity {tenant: $tenant}) "
        "RETURN e.id AS entityId, e.label AS label, e.type AS type, "
        "e.aliases AS aliases, e.description AS description"
    )
    body = json.dumps({"statements": [{"statement": statement, "parameters": {"tenant": tenant}}]}).encode()
    request = urllib.request.Request(
        f"{base}/db/{database}/tx/commit", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    if user:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read())
    if payload.get("errors"):
        raise SystemExit(f"Neo4j query failed: {payload['errors']}")
    columns = payload["results"][0]["columns"]
    return [dict(zip(columns, row["row"])) for row in payload["results"][0]["data"]]


def normalize(entity: dict) -> dict:
    aliases = entity.get("aliases")
    if isinstance(aliases, str):
        aliases = [part.strip() for part in aliases.split("|") if part.strip()]
    return {
        "entityId": entity.get("entityId"),
        "label": entity.get("label") or entity.get("entityId"),
        "aliases": aliases or [],
        "type": entity.get("type") or "",
        "description": entity.get("description") or "",
    }


def main() -> None:
    tenant = os.environ.get("TENANT", "demo")
    secret = os.environ.get("SYNANTON_JWT_SECRET", "")
    if len(secret) < 32:
        raise SystemExit("SYNANTON_JWT_SECRET (>=32 chars) is required")
    entities = [normalize(entity) for entity in neo4j_entities(tenant)]
    body = json.dumps({"entities": entities}).encode()
    request = urllib.request.Request(
        os.environ.get("SYNQUEST_URL", "http://localhost:8083").rstrip("/")
        + "/v1/search-projection/entities",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + mint_token(tenant, secret),
        },
        method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.loads(response.read())
    print(json.dumps(result, indent=2))
    if result.get("entities", 0) != len(entities):
        raise SystemExit(f"projected {result.get('entities', 0)} of {len(entities)} entities")


if __name__ == "__main__":
    main()
